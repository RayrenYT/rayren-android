package validation

import (
    "context"
    "crypto/tls"
    "crypto/x509"
    "encoding/binary"
    "encoding/json"
    "fmt"
    "io"
    "net"
    "net/http"
    "net/http/httptest"
    "os"
    "path/filepath"
    "strings"
    "testing"
    "time"
    "github.com/xtls/xray-core/core"
    "github.com/xtls/xray-core/infra/conf/serial"
    _ "github.com/xtls/xray-core/main/distro/all"
    "golang.org/x/net/proxy"
    "golang.org/x/net/dns/dnsmessage"
)

func fixture(t *testing.T,name string) string {t.Helper();raw,err:=os.ReadFile(filepath.Join(os.Getenv("RAYREN_FIXTURES"),name));if err!=nil{t.Fatal(err)};return string(raw)}
func TestGeneratedConfigurations(t *testing.T){
    paths,err:=filepath.Glob(filepath.Join(os.Getenv("RAYREN_FIXTURES"),"*.json"));if err!=nil||len(paths)<10{t.Fatal("Missing generated fixtures")}
    for _,path:=range paths {t.Run(filepath.Base(path),func(t *testing.T){raw,err:=os.ReadFile(path);if err!=nil{t.Fatal(err)};conf,err:=serial.LoadJSONConfig(strings.NewReader(string(raw)));if err!=nil{t.Fatal(err)};instance,err:=core.New(conf);if err!=nil{t.Fatal(err)};_ = instance.Close()})}
}
func port(t *testing.T) int {t.Helper();l,err:=net.Listen("tcp","127.0.0.1:0");if err!=nil{t.Fatal(err)};p:=l.Addr().(*net.TCPAddr).Port;_ = l.Close();return p}
func start(t *testing.T,raw string)*core.Instance{t.Helper();if os.Getenv("RAYREN_TEST_DEBUG")=="1"{raw=strings.ReplaceAll(raw,`"loglevel":"none"`,`"loglevel":"debug"`)};conf,err:=serial.LoadJSONConfig(strings.NewReader(raw));if err!=nil{t.Fatal(err)};instance,err:=core.New(conf);if err!=nil{t.Fatal(err)};if err=instance.Start();err!=nil{_ = instance.Close();t.Fatal(err)};t.Cleanup(func(){_ = instance.Close()});return instance}

func TestActualVlessSocksDnsAndFailClosed(t *testing.T){
    // Local controlled endpoints, no user subscription or production server is involved.
    target:=httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){w.WriteHeader(204)}))
    // Optional client auth prevents local HTTPS inspection from substituting fixture certificates.
    // Clients still validate only the fixture root, with no insecure TLS mode.
    target.TLS=&tls.Config{ClientAuth:tls.RequestClientCert,MaxVersion:tls.VersionTLS12};target.StartTLS();defer target.Close()
    dns,err:=net.ListenPacket("udp","127.0.0.1:0");if err!=nil{t.Fatal(err)};defer dns.Close()
    go func(){buffer:=make([]byte,2048);for{n,addr,err:=dns.ReadFrom(buffer);if err!=nil{return};var query dnsmessage.Message;if query.Unpack(buffer[:n])!=nil||len(query.Questions)==0{continue};q:=query.Questions[0];answer:=dnsmessage.Message{Header:dnsmessage.Header{ID:query.Header.ID,Response:true,RecursionDesired:true,RecursionAvailable:true},Questions:query.Questions,Answers:[]dnsmessage.Resource{{Header:dnsmessage.ResourceHeader{Name:q.Name,Type:dnsmessage.TypeA,Class:dnsmessage.ClassINET,TTL:60},Body:&dnsmessage.AResource{A:[4]byte{203,0,113,77}}}}};b,_:=answer.Pack();_,_=dns.WriteTo(b,addr)}}()
    serverPort,vpnPort,probePort:=port(t),port(t),port(t)
    start(t,fmt.Sprintf(`{"log":{"loglevel":"none"},"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"vless","settings":{"decryption":"none","clients":[{"id":"11111111-1111-4111-8111-111111111111"}]}}],"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["127.0.0.1/32"]}]}}]}`,serverPort))
    raw:=fixture(t,"protocol-1.json")
    var conf map[string]interface{};if json.Unmarshal([]byte(raw),&conf)!=nil{t.Fatal("fixture JSON")}
    inbound:=conf["inbounds"].([]interface{});inbound[0].(map[string]interface{})["port"]=vpnPort;inbound[1].(map[string]interface{})["port"]=probePort
    outbound:=conf["outbounds"].([]interface{})[0].(map[string]interface{});outbound["settings"].(map[string]interface{})["vnext"].([]interface{})[0].(map[string]interface{})["port"]=serverPort
    conf["dns"].(map[string]interface{})["servers"]=[]interface{}{map[string]interface{}{"address":"127.0.0.1","port":dns.LocalAddr().(*net.UDPAddr).Port}}
    encoded,_:=json.Marshal(conf);clientCore:=start(t,string(encoded))
    socks,err:=proxy.SOCKS5("tcp",fmt.Sprintf("127.0.0.1:%d",probePort),&proxy.Auth{User:"testuser",Password:"testpassword"},&net.Dialer{Timeout:3*time.Second});if err!=nil{t.Fatal(err)}
    roots:=x509.NewCertPool();roots.AddCert(target.Certificate())
    transport:=&http.Transport{DialContext:socks.(proxy.ContextDialer).DialContext,TLSClientConfig:&tls.Config{ServerName:"example.com",RootCAs:roots,MinVersion:tls.VersionTLS12},DisableKeepAlives:true}
    defer transport.CloseIdleConnections();client:=&http.Client{Transport:transport,Timeout:5*time.Second}
    response,err:=client.Get(target.URL);if err!=nil{t.Fatal(err)};_ = response.Body.Close();if response.StatusCode!=204{t.Fatalf("Probe status %d",response.StatusCode)}
    wrong,_:=proxy.SOCKS5("tcp",fmt.Sprintf("127.0.0.1:%d",probePort),&proxy.Auth{User:"wrong",Password:"wrong"},&net.Dialer{Timeout:time.Second});if conn,err:=wrong.Dial("tcp",strings.TrimPrefix(target.URL,"https://"));err==nil{_ = conn.Close();t.Fatal("SOCKS accepted invalid credentials")}
    vpn,_:=proxy.SOCKS5("tcp",fmt.Sprintf("127.0.0.1:%d",vpnPort),&proxy.Auth{User:"testuser",Password:"testpassword"},&net.Dialer{Timeout:2*time.Second})
    dnsConn,err:=vpn.(proxy.ContextDialer).DialContext(context.Background(),"tcp","8.8.8.8:53");if err!=nil{t.Fatal(err)};defer dnsConn.Close();_ = dnsConn.SetDeadline(time.Now().Add(5*time.Second))
    question:=dnsmessage.Message{Header:dnsmessage.Header{ID:4321,RecursionDesired:true},Questions:[]dnsmessage.Question{{Name:dnsmessage.MustNewName("fixture.example."),Type:dnsmessage.TypeA,Class:dnsmessage.ClassINET}}};query,_:=question.Pack();packet:=make([]byte,len(query)+2);binary.BigEndian.PutUint16(packet,uint16(len(query)));copy(packet[2:],query);if _,err=dnsConn.Write(packet);err!=nil{t.Fatal(err)}
    var size [2]byte;if _,err=io.ReadFull(dnsConn,size[:]);err!=nil{t.Fatal(err)};reply:=make([]byte,int(binary.BigEndian.Uint16(size[:])));if _,err=io.ReadFull(dnsConn,reply);err!=nil{t.Fatal(err)};var answer dnsmessage.Message;if answer.Unpack(reply)!=nil||len(answer.Answers)==0{t.Fatal("DNS interception did not answer")};if record,ok:=answer.Answers[0].Body.(*dnsmessage.AResource);!ok||record.A!=[4]byte{203,0,113,77}{t.Fatal("DNS escaped controlled resolver")}
    _=clientCore.Close()
    if response,err=client.Get(target.URL);err==nil{_ = response.Body.Close();t.Fatal("Traffic succeeded after transport shutdown")}
}

func TestBypassDirectAndExplicitBlockWithUnavailableProxy(t *testing.T){
    target:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){w.WriteHeader(204)}));defer target.Close()
    var conf map[string]interface{}
    if err:=json.Unmarshal([]byte(fixture(t,"bypass-groups.json")),&conf);err!=nil{t.Fatal(err)}
    vpnPort,probePort:=port(t),port(t)
    inbounds:=conf["inbounds"].([]interface{});inbounds[0].(map[string]interface{})["port"]=vpnPort;inbounds[1].(map[string]interface{})["port"]=probePort
    proxyConfig:=conf["outbounds"].([]interface{})[0].(map[string]interface{})
    // Deliberately unavailable proxy; successful bypass cannot be a proxy fallback.
    proxyConfig["protocol"]="socks";proxyConfig["settings"]=map[string]interface{}{"servers":[]interface{}{map[string]interface{}{"address":"127.0.0.1","port":port(t)}}};delete(proxyConfig,"streamSettings")
    conf["dns"].(map[string]interface{})["hosts"]=map[string]interface{}{"my.example":"127.0.0.1","vk.com":"127.0.0.1","unmatched.example":"127.0.0.1"}
    encoded,_:=json.Marshal(conf);start(t,string(encoded))
    dialer,err:=proxy.SOCKS5("tcp",fmt.Sprintf("127.0.0.1:%d",vpnPort),&proxy.Auth{User:"testuser",Password:"testpassword"},&net.Dialer{Timeout:time.Second});if err!=nil{t.Fatal(err)}
    transport:=&http.Transport{DialContext:dialer.(proxy.ContextDialer).DialContext,DisableKeepAlives:true};defer transport.CloseIdleConnections()
    client:=&http.Client{Transport:transport,Timeout:3*time.Second}
    for _,domain:=range []string{"my.example","vk.com","unmatched.example"}{
        response,err:=client.Get(strings.Replace(target.URL,"127.0.0.1",domain,1))
        if domain=="my.example"{if err!=nil{t.Fatal("Direct bypass failed:",err)};response.Body.Close();if response.StatusCode!=204{t.Fatal("Wrong direct status")}}
        if domain!="my.example"&&err==nil{response.Body.Close();t.Fatalf("%s unexpectedly bypassed policy",domain)}
    }
}

func TestSmartBalanceSelectsWorkingMemberWithFirstUnavailable(t *testing.T){
    target:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){w.WriteHeader(204)}));defer target.Close()
    serverPort,vpnPort,probePort:=port(t),port(t),port(t)
    start(t,fmt.Sprintf(`{"log":{"loglevel":"none"},"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"vless","settings":{"decryption":"none","clients":[{"id":"11111111-1111-4111-8111-111111111111"}]}}],"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["127.0.0.1/32"]}]}}]}`,serverPort))
    var conf map[string]interface{}
    if err:=json.Unmarshal([]byte(fixture(t,"smart-balance.json")),&conf);err!=nil{t.Fatal(err)}
    inbounds:=conf["inbounds"].([]interface{});inbounds[0].(map[string]interface{})["port"]=vpnPort;inbounds[1].(map[string]interface{})["port"]=probePort
    outbounds:=conf["outbounds"].([]interface{})
    for i,p:=range []int{port(t),serverPort}{outbounds[i].(map[string]interface{})["settings"].(map[string]interface{})["vnext"].([]interface{})[0].(map[string]interface{})["port"]=p}
    observation:=conf["observatory"].(map[string]interface{})
    // Controlled local HTTP endpoint only in the test; production fixture uses HTTPS gstatic.
    observation["probeUrl"]=target.URL;observation["probeInterval"]="100ms"
    encoded,_:=json.Marshal(conf);instance:=start(t,string(encoded))
    socks,_:=proxy.SOCKS5("tcp",fmt.Sprintf("127.0.0.1:%d",probePort),&proxy.Auth{User:"testuser",Password:"testpassword"},&net.Dialer{Timeout:time.Second})
    transport:=&http.Transport{DialContext:socks.(proxy.ContextDialer).DialContext,DisableKeepAlives:true};defer transport.CloseIdleConnections()
    client:=&http.Client{Transport:transport,Timeout:time.Second}
    deadline:=time.Now().Add(10*time.Second);success:=false
    for time.Now().Before(deadline){resp,err:=client.Get(target.URL);if err==nil{resp.Body.Close();success=resp.StatusCode==204;if success{break}};time.Sleep(100*time.Millisecond)}
    if !success{t.Fatal("Balancer did not choose healthy second member")}
    _=instance.Close()
    if resp,err:=client.Get(target.URL);err==nil{resp.Body.Close();t.Fatal("Balancer traffic escaped after shutdown")}
}

func TestWhitelistUsesPhysicalDnsAndDirectWhileProxyIsUnavailable(t *testing.T){
    target:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){w.WriteHeader(204)}));defer target.Close()
    dns,err:=net.ListenPacket("udp","127.0.0.1:0");if err!=nil{t.Fatal(err)};defer dns.Close()
    queries:=make(chan string,16)
    go func(){
        buffer:=make([]byte,2048)
        for{n,addr,err:=dns.ReadFrom(buffer);if err!=nil{return}
            var query dnsmessage.Message;if query.Unpack(buffer[:n])!=nil||len(query.Questions)==0{continue}
            q:=query.Questions[0];select{case queries<-q.Name.String():default:}
            answer:=dnsmessage.Message{Header:dnsmessage.Header{ID:query.Header.ID,Response:true,RecursionDesired:true,RecursionAvailable:true},Questions:query.Questions,Answers:[]dnsmessage.Resource{{Header:dnsmessage.ResourceHeader{Name:q.Name,Type:dnsmessage.TypeA,Class:dnsmessage.ClassINET,TTL:60},Body:&dnsmessage.AResource{A:[4]byte{127,0,0,1}}}}}
            b,_:=answer.Pack();_,_=dns.WriteTo(b,addr)
        }
    }()
    var conf map[string]interface{}
    if err=json.Unmarshal([]byte(fixture(t,"whitelist.json")),&conf);err!=nil{t.Fatal(err)}
    vpnPort:=port(t)
    conf["inbounds"].([]interface{})[0].(map[string]interface{})["port"]=vpnPort
    conf["inbounds"].([]interface{})[1].(map[string]interface{})["port"]=port(t)
    outbound:=conf["outbounds"].([]interface{})[0].(map[string]interface{})
    outbound["protocol"]="socks";outbound["settings"]=map[string]interface{}{"servers":[]interface{}{map[string]interface{}{"address":"127.0.0.1","port":port(t)}}};delete(outbound,"streamSettings")
    servers:=conf["dns"].(map[string]interface{})["servers"].([]interface{})
    servers[0].(map[string]interface{})["address"]="127.0.0.1"
    servers[0].(map[string]interface{})["port"]=dns.LocalAddr().(*net.UDPAddr).Port
    servers[len(servers)-1].(map[string]interface{})["address"]=fmt.Sprintf("tcp://127.0.0.1:%d",port(t))
    encoded,_:=json.Marshal(conf);start(t,string(encoded))
    dialer,_:=proxy.SOCKS5("tcp",fmt.Sprintf("127.0.0.1:%d",vpnPort),&proxy.Auth{User:"testuser",Password:"testpassword"},&net.Dialer{Timeout:time.Second})
    transport:=&http.Transport{DialContext:dialer.(proxy.ContextDialer).DialContext,DisableKeepAlives:true};defer transport.CloseIdleConnections()
    client:=&http.Client{Transport:transport,Timeout:3*time.Second}
    response,err:=client.Get(strings.Replace(target.URL,"127.0.0.1","vk.com",1))
    if err!=nil{t.Fatal("whitelisted site/DNS did not bypass unavailable proxy",err)}
    response.Body.Close();if response.StatusCode!=204{t.Fatal("unexpected status")}
    select{case name:=<-queries:if name!="vk.com."{t.Fatal("wrong physical DNS query",name)};case <-time.After(time.Second):t.Fatal("physical DNS was not used")}
    if response,err=client.Get(strings.Replace(target.URL,"127.0.0.1","unmatched.example",1));err==nil{response.Body.Close();t.Fatal("unlisted site escaped VPN")}
    for{select{case name:=<-queries:if name!="vk.com."{t.Fatal("unlisted query escaped to physical DNS",name)};default:return}}
}

func TestProxyHostnameDoesNotRequireClientDnsRoundTrip(t *testing.T){
    target:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){w.WriteHeader(204)}));defer target.Close()
    serverPort,vpnPort,probePort:=port(t),port(t),port(t)
    // Only the remote proxy knows how to reach this otherwise unresolvable destination.
    start(t,fmt.Sprintf(`{"log":{"loglevel":"none"},"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"vless","settings":{"decryption":"none","clients":[{"id":"11111111-1111-4111-8111-111111111111"}]}}],"outbounds":[{"protocol":"freedom","settings":{"redirect":"%s","finalRules":[{"action":"allow","ip":["127.0.0.1/32"]}]}}]}`,serverPort,strings.TrimPrefix(target.URL,"http://")))
    var conf map[string]interface{}
    if err:=json.Unmarshal([]byte(fixture(t,"protocol-1.json")),&conf);err!=nil{t.Fatal(err)}
    inbounds:=conf["inbounds"].([]interface{});inbounds[0].(map[string]interface{})["port"]=vpnPort;inbounds[1].(map[string]interface{})["port"]=probePort
    conf["outbounds"].([]interface{})[0].(map[string]interface{})["settings"].(map[string]interface{})["vnext"].([]interface{})[0].(map[string]interface{})["port"]=serverPort
    conf["dns"].(map[string]interface{})["servers"]=[]interface{}{map[string]interface{}{"address":"127.0.0.1","port":port(t)}}
    encoded,_:=json.Marshal(conf);start(t,string(encoded))
    socks,_:=proxy.SOCKS5("tcp",fmt.Sprintf("127.0.0.1:%d",probePort),&proxy.Auth{User:"testuser",Password:"testpassword"},&net.Dialer{Timeout:time.Second})
    transport:=&http.Transport{DialContext:socks.(proxy.ContextDialer).DialContext,DisableKeepAlives:true};defer transport.CloseIdleConnections()
    client:=&http.Client{Transport:transport,Timeout:3*time.Second}
    response,err:=client.Get("http://remote-only.invalid/generate_204")
    if err!=nil{t.Fatal("Unnecessary client DNS prevented proxy request:",err)}
    response.Body.Close();if response.StatusCode!=204{t.Fatal("Proxy response status")}
}

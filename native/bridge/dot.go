package rayrencore

import (
    "bytes"
    "context"
    "crypto/tls"
    "crypto/x509"
    "encoding/binary"
    "errors"
    "io"
    "net"
    "net/url"
    "strconv"
    "sync"
    "time"
    "golang.org/x/net/proxy"
)

// dotServer accepts framed DNS only on loopback and forwards it over authenticated
// SOCKS + certificate-verified TLS. It never falls back to the device resolver.
type dotServer struct { listener net.Listener; cancel context.CancelFunc; connections sync.Map; workers sync.WaitGroup }
var dotMu sync.Mutex
var dot *dotServer

func StartDoT(localPort,proxyPort int,user,password,endpoint string) error {
    return startDoT(localPort,proxyPort,user,password,endpoint,nil)
}

// roots is only an internal test seam; the mobile API always uses platform trust.
func startDoT(localPort,proxyPort int,user,password,endpoint string,roots *x509.CertPool) error {
    dotMu.Lock();defer dotMu.Unlock()
    if dot!=nil {return errors.New("DOT_ALREADY_RUNNING")}
    u,err:=url.Parse(endpoint)
    if err!=nil||u.Scheme!="tls"||u.Hostname()==""||u.User!=nil||u.Path!=""||u.RawQuery!=""||u.Fragment!=""||(u.Port()!=""&&u.Port()!="853") {return errors.New("DOT_ENDPOINT_INVALID")}
    if localPort<1024||localPort>65535||proxyPort<1024||proxyPort>65535||user==""||password=="" {return errors.New("DOT_PARAMETERS_INVALID")}
    pd,err:=proxy.SOCKS5("tcp",net.JoinHostPort("127.0.0.1",strconv.Itoa(proxyPort)),&proxy.Auth{User:user,Password:password},&net.Dialer{Timeout:10*time.Second})
    if err!=nil {return errors.New("DOT_PROXY_INVALID")}
    cd,ok:=pd.(proxy.ContextDialer);if !ok{return errors.New("DOT_PROXY_INVALID")}
    listener,err:=net.Listen("tcp",net.JoinHostPort("127.0.0.1",strconv.Itoa(localPort)));if err!=nil{return errors.New("DOT_LISTEN_FAILED")}
    ctx,cancel:=context.WithCancel(context.Background())
    server:=&dotServer{listener:listener,cancel:cancel};dot=server
    slots:=make(chan struct{},16)
    server.workers.Add(1)
    go func(){
        defer server.workers.Done()
        for {
            client,err:=listener.Accept();if err!=nil{return}
            select{case slots<-struct{}{}:default:_=client.Close();continue}
            server.connections.Store(client,true);server.workers.Add(1)
            go func(){
                defer server.workers.Done();defer func(){<-slots}();defer server.connections.Delete(client);defer client.Close()
                // A short deadline also bounds abusive local clients and shutdown races.
                _=client.SetDeadline(time.Now().Add(15*time.Second))
                var length [2]byte
                if _,err:=io.ReadFull(client,length[:]);err!=nil{return}
                n:=int(binary.BigEndian.Uint16(length[:]));if n<12{return}
                message:=make([]byte,n);if _,err:=io.ReadFull(client,message);err!=nil{return}
                requestCtx,done:=context.WithTimeout(ctx,12*time.Second);defer done()
                conn,err:=cd.DialContext(requestCtx,"tcp",net.JoinHostPort(u.Hostname(),"853"));if err!=nil{return}
                server.connections.Store(conn,true);defer server.connections.Delete(conn);defer conn.Close()
                _=conn.SetDeadline(time.Now().Add(12*time.Second))
                secured:=tls.Client(conn,&tls.Config{ServerName:u.Hostname(),MinVersion:tls.VersionTLS12,RootCAs:roots})
                if err:=secured.HandshakeContext(requestCtx);err!=nil{return}
                packet:=append(length[:],message...)
                if _,err:=io.Copy(secured,bytes.NewReader(packet));err!=nil{return}
                if _,err:=io.ReadFull(secured,length[:]);err!=nil{return}
                response:=make([]byte,int(binary.BigEndian.Uint16(length[:])));if len(response)<12{return}
                if _,err:=io.ReadFull(secured,response);err!=nil{return}
                if response[0]!=message[0]||response[1]!=message[1]||response[2]&0x80==0{return}
                _,_=io.Copy(client,bytes.NewReader(append(length[:],response...)))
            }()
        }
    }()
    return nil
}

func StopDoT(){
    dotMu.Lock();defer dotMu.Unlock()
    if dot==nil{return}
    dot.cancel();_ = dot.listener.Close()
    dot.connections.Range(func(key,value interface{})bool{_ = key.(net.Conn).Close();return true})
    dot.workers.Wait();dot=nil
}

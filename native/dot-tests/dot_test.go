package rayrencore

import (
    "crypto/tls"
    "crypto/x509"
    "encoding/binary"
    "fmt"
    "io"
    "net"
    "net/http"
    "net/http/httptest"
    "strings"
    "testing"
    "time"
    "github.com/xtls/xray-core/core"
    "github.com/xtls/xray-core/infra/conf/serial"
    _ "github.com/xtls/xray-core/main/distro/all"
)
func freePort(t *testing.T)int{t.Helper();l,e:=net.Listen("tcp","127.0.0.1:0");if e!=nil{t.Fatal(e)};p:=l.Addr().(*net.TCPAddr).Port;_ = l.Close();return p}
func resolverFixture(t *testing.T)(int,*x509.CertPool){
    t.Helper();certServer:=httptest.NewUnstartedServer(http.HandlerFunc(func(http.ResponseWriter,*http.Request){}));certServer.TLS=&tls.Config{ClientAuth:tls.RequestClientCert,MaxVersion:tls.VersionTLS12};certServer.StartTLS();config:=certServer.TLS.Clone();roots:=x509.NewCertPool();roots.AddCert(certServer.Certificate());certServer.Close()
    listener,err:=tls.Listen("tcp","127.0.0.1:0",config);if err!=nil{t.Fatal(err)};t.Cleanup(func(){_ = listener.Close()})
    go func(){for{conn,err:=listener.Accept();if err!=nil{return};go func(){defer conn.Close();_ = conn.SetDeadline(time.Now().Add(3*time.Second));var header [2]byte;if _,e:=io.ReadFull(conn,header[:]);e!=nil{return};data:=make([]byte,int(binary.BigEndian.Uint16(header[:])));if _,e:=io.ReadFull(conn,data);e!=nil{return};if len(data)<12{return};data[2]|=0x80;_,_=conn.Write(append(header[:],data...))}()}}()
    proxyPort:=freePort(t)
    raw:=fmt.Sprintf(`{"log":{"loglevel":"none"},"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"socks","settings":{"auth":"password","accounts":[{"user":"test","pass":"secret"}]}}],"outbounds":[{"protocol":"freedom","settings":{"redirect":"%s"}}]}`,proxyPort,listener.Addr().String())
    conf,err:=serial.LoadJSONConfig(strings.NewReader(raw));if err!=nil{t.Fatal(err)};instance,err:=core.New(conf);if err!=nil{t.Fatal(err)};if instance.Start()!=nil{t.Fatal("start proxy")};t.Cleanup(func(){_ = instance.Close()})
    return proxyPort,roots
}
func query(port int)([]byte,error){conn,err:=net.DialTimeout("tcp",fmt.Sprintf("127.0.0.1:%d",port),time.Second);if err!=nil{return nil,err};defer conn.Close();_ = conn.SetDeadline(time.Now().Add(3*time.Second));request:=[]byte{0,12,0x12,0x34,1,0,0,0,0,0,0,0,0,0};if _,err=conn.Write(request);err!=nil{return nil,err};response:=make([]byte,len(request));_,err=io.ReadFull(conn,response);return response,err}
func TestDoTVerifiedTLSOverAuthenticatedSocks(t *testing.T){proxyPort,roots:=resolverFixture(t);localPort:=freePort(t);if err:=startDoT(localPort,proxyPort,"test","secret","tls://example.com:853",roots);err!=nil{t.Fatal(err)};defer StopDoT();response,err:=query(localPort);if err!=nil{t.Fatal(err)};if response[2]!=0x12||response[3]!=0x34||response[4]&0x80==0{t.Fatal("wrong DNS response")}}
func TestDoTRejectsUntrustedCertificate(t *testing.T){proxyPort,_:=resolverFixture(t);localPort:=freePort(t);if err:=startDoT(localPort,proxyPort,"test","secret","tls://example.com:853",x509.NewCertPool());err!=nil{t.Fatal(err)};defer StopDoT();if _,err:=query(localPort);err==nil{t.Fatal("accepted untrusted TLS")}}
func TestDoTRejectsWrongProxyPassword(t *testing.T){proxyPort,roots:=resolverFixture(t);localPort:=freePort(t);if err:=startDoT(localPort,proxyPort,"test","wrong","tls://example.com:853",roots);err!=nil{t.Fatal(err)};defer StopDoT();if _,err:=query(localPort);err==nil{t.Fatal("accepted wrong SOCKS credentials")}}
func TestDoTValidationAndLifecycle(t *testing.T){for _,endpoint:=range []string{"http://1.1.1.1","tls://1.1.1.1:443","tls://name:pass@1.1.1.1","tls://1.1.1.1?insecure=1"}{if StartDoT(10812,10809,"u","p",endpoint)==nil{StopDoT();t.Fatal("accepted unsafe endpoint")}};p:=freePort(t);if StartDoT(p,10809,"u","p","tls://1.1.1.1")!=nil{t.Fatal("start")};if StartDoT(p,10809,"u","p","tls://1.1.1.1")==nil{t.Fatal("double start")};StopDoT();StopDoT();if _,err:=net.DialTimeout("tcp",fmt.Sprintf("127.0.0.1:%d",p),time.Second);err==nil{t.Fatal("listener remains after stop")}}

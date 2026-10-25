package rayrencore

import (
    "context"
    "crypto/tls"
    "encoding/json"
    "errors"
    "io"
    "net"
    "net/http"
    "net/http/httptrace"
    "net/url"
    "strconv"
    "strings"
    "sync"
    "time"
    "golang.org/x/net/proxy"
)

var requestMu sync.Mutex
var requestContext, requestCancel = context.WithCancel(context.Background())

// CancelRequests is safe to call from Android's main thread while a probe blocks
// the controller thread. Cancellation remains latched until the next route starts.
func CancelRequests() { requestMu.Lock(); requestCancel(); requestMu.Unlock() }
func BeginRequests() {
    requestMu.Lock(); defer requestMu.Unlock()
    requestCancel()
    requestContext, requestCancel = context.WithCancel(context.Background())
}
func currentRequestContext() context.Context {
    requestMu.Lock(); defer requestMu.Unlock(); return requestContext
}

func proxyClient(port int,user,password string,timeout int) (*http.Client,error) {
    if port<1024 || port>65535 || timeout<1000 || timeout>60000 { return nil,errors.New("PROBE_PARAMETERS_INVALID") }
    dialer,err:=proxy.SOCKS5("tcp",net.JoinHostPort("127.0.0.1",strconv.Itoa(port)),&proxy.Auth{User:user,Password:password},&net.Dialer{Timeout:time.Duration(timeout)*time.Millisecond})
    if err!=nil { return nil,errors.New("PROBE_PROXY_INVALID") }
    cd,ok:=dialer.(proxy.ContextDialer)
    if !ok { return nil,errors.New("PROBE_CONTEXT_UNAVAILABLE") }
    tr:=&http.Transport{DialContext:cd.DialContext,DisableKeepAlives:true,TLSHandshakeTimeout:time.Duration(timeout)*time.Millisecond}
    return &http.Client{Transport:tr,Timeout:time.Duration(timeout)*time.Millisecond,CheckRedirect:func(_ *http.Request,_ []*http.Request) error{return http.ErrUseLastResponse}},nil
}

// Probe performs a fresh HTTPS GET through SOCKS, including target TLS.
func Probe(port int,user,password,target string,timeout,expectedStatus int) (int64,error) {
    report,err:=measureProxy(port,user,password,target,timeout,expectedStatus)
    return report.TotalMs,err
}

// ProbeReport contains durations only, never URLs, credentials or addresses.
func ProbeReport(port int,user,password,target string,timeout,expectedStatus int) (string,error) {
    report,err:=measureProxy(port,user,password,target,timeout,expectedStatus)
    if err!=nil{return "",err}
    data,_:=json.Marshal(report)
    return string(data),nil
}

type probeTiming struct {
    TotalMs int64 `json:"totalMs"`
    ConnectMs int64 `json:"connectMs"`
    TLSMs int64 `json:"tlsMs"`
    ResponseMs int64 `json:"responseMs"`
}

func measureProxy(port int,user,password,target string,timeout,expectedStatus int) (probeTiming,error) {
    u,err:=url.Parse(target)
    if err!=nil || u.Scheme!="https" || u.Hostname()=="" || u.User!=nil || len(target)>2048 { return probeTiming{},errors.New("PROBE_URL_INVALID") }
    c,err:=proxyClient(port,user,password,timeout); if err!=nil{return probeTiming{},err}
    defer c.CloseIdleConnections()
    return timedGet(c,target,expectedStatus)
}

func timedGet(c *http.Client,target string,expectedStatus int) (probeTiming,error) {
    var mu sync.Mutex
    var connected,tlsStart,tlsEnd,firstByte time.Time
    start:=time.Now()
    trace:=&httptrace.ClientTrace{
        // GotConn runs after SOCKS negotiation and target TLS for HTTPS.
        GotConn:func(httptrace.GotConnInfo){mu.Lock();connected=time.Now();mu.Unlock()},
        TLSHandshakeStart:func(){mu.Lock();tlsStart=time.Now();mu.Unlock()},
        TLSHandshakeDone:func(tls.ConnectionState,error){mu.Lock();tlsEnd=time.Now();mu.Unlock()},
        GotFirstResponseByte:func(){mu.Lock();firstByte=time.Now();mu.Unlock()},
    }
    ctx:=httptrace.WithClientTrace(currentRequestContext(),trace)
    req,err:=http.NewRequestWithContext(ctx,http.MethodGet,target,nil)
    if err!=nil{return probeTiming{},errors.New("PROBE_URL_INVALID")}
    response,err:=c.Do(req)
    if err!=nil{return probeTiming{},errors.New("PROBE_NETWORK_FAILED")}
    defer response.Body.Close()
    if response.StatusCode!=expectedStatus{return probeTiming{},errors.New("PROBE_STATUS_FAILED")}
    if _,err=io.Copy(io.Discard,io.LimitReader(response.Body,4096));err!=nil{return probeTiming{},errors.New("PROBE_BODY_FAILED")}
    end:=time.Now()
    mu.Lock();defer mu.Unlock()
    report:=probeTiming{TotalMs:end.Sub(start).Milliseconds()}
    if !tlsStart.IsZero(){report.ConnectMs=tlsStart.Sub(start).Milliseconds()}
    if !tlsEnd.IsZero(){report.TLSMs=tlsEnd.Sub(tlsStart).Milliseconds()}
    if !firstByte.IsZero()&&!connected.IsZero(){report.ResponseMs=firstByte.Sub(connected).Milliseconds()}
    return report,nil
}

func ExitIP(port int,user,password string) string {
    c,err:=proxyClient(port,user,password,8000); if err!=nil{return ""}
    defer c.CloseIdleConnections()
    req,err:=http.NewRequestWithContext(currentRequestContext(),http.MethodGet,"https://api.ipify.org",nil); if err!=nil{return ""}
    resp,err:=c.Do(req); if err!=nil{return ""}
    defer resp.Body.Close()
    if resp.StatusCode!=200{return ""}
    b,err:=io.ReadAll(io.LimitReader(resp.Body,64)); if err!=nil{return ""}
    ip:=net.ParseIP(strings.TrimSpace(string(b))); if ip==nil{return ""}
    return ip.String()
}

package rayrencore

import (
    "context"
    "crypto/tls"
    "crypto/x509"
    "net/http"
    "net/http/httptest"
    "testing"
    "time"
)

func TestCancelledProviderStartupReleasesCallerAndCleansLateResult(t *testing.T) {
    ctx,cancel:=context.WithCancel(context.Background())
    gate:=make(chan struct{},1)
    entered,release,stopped:=make(chan struct{}),make(chan struct{}),make(chan struct{})
    done:=make(chan error,1)
    go func(){done<-runStartup(ctx,gate,func()error{close(entered);<-release;return nil},func(){close(stopped)})}()
    <-entered
    cancel()
    select{case err:=<-done:if err==nil{t.Fatal("cancel accepted result")};case <-time.After(time.Second):t.Fatal("caller blocked on provider")}
    other,cancelOther:=context.WithCancel(context.Background());cancelOther()
    if err:=runStartup(other,gate,func()error{t.Error("second provider started before old cleanup");return nil},func(){});err==nil{t.Fatal("cancelled waiter ran")}
    close(release)
    select{case <-stopped:case <-time.After(time.Second):t.Fatal("late provider not cleaned")}
    next,finish:=context.WithTimeout(context.Background(),time.Second);defer finish()
    if err:=runStartup(next,gate,func()error{return nil},func(){});err!=nil{t.Fatal(err)}
}

func TestTimingIncludesResponseDelayAndUsesGet(t *testing.T) {
    target:=httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
        if r.Method!="GET"{t.Errorf("Expected GET, got %s",r.Method)}
        time.Sleep(80*time.Millisecond)
        w.WriteHeader(204)
    }))
    target.TLS=&tls.Config{ClientAuth:tls.RequestClientCert,MaxVersion:tls.VersionTLS12};target.StartTLS()
    defer target.Close()
    roots:=x509.NewCertPool();roots.AddCert(target.Certificate())
    tr:=&http.Transport{TLSClientConfig:&tls.Config{ServerName:"example.com",RootCAs:roots},DisableKeepAlives:true}
    defer tr.CloseIdleConnections()
    client:=&http.Client{Transport:tr,Timeout:3*time.Second}
    result,err:=timedGet(client,target.URL,204)
    if err!=nil{t.Fatal(err)}
    if result.TotalMs<80||result.ResponseMs<75||result.TLSMs>result.TotalMs{t.Fatalf("Timing: %+v",result)}
}

func TestProbeRejectsHttpCredentialsAndInvalidParameters(t *testing.T) {
    for _,url:=range []string{"http://example.com","https://user:secret@example.com","https:///"}{
        if _,err:=Probe(10809,"u","p",url,1000,204);err==nil{t.Fatal("Accepted invalid URL")}
    }
    if _,err:=proxyClient(80,"u","p",1000);err==nil{t.Fatal("Accepted port")}
}

func TestProbeDoesNotFollowRedirectOrTrustSelfSignedCertificate(t *testing.T) {
    target:=httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){http.Redirect(w,r,"/unexpected",302)}))
    target.TLS=&tls.Config{ClientAuth:tls.RequestClientCert,MaxVersion:tls.VersionTLS12};target.StartTLS()
    defer target.Close()
    c,_:=proxyClient(10809,"u","p",1000)
    if c.CheckRedirect(nil,nil)!=http.ErrUseLastResponse{t.Fatal("Redirect policy changed")}
    c.Transport=&http.Transport{DisableKeepAlives:true}
    defer c.CloseIdleConnections()
    if _,err:=timedGet(c,target.URL,204);err==nil{t.Fatal("Accepted untrusted TLS")}
    roots:=x509.NewCertPool();roots.AddCert(target.Certificate())
    c.Transport=&http.Transport{TLSClientConfig:&tls.Config{ServerName:"example.com",RootCAs:roots},DisableKeepAlives:true}
    defer c.CloseIdleConnections()
    if _,err:=timedGet(c,target.URL,204);err==nil||err.Error()!="PROBE_STATUS_FAILED"{t.Fatalf("Expected rejected redirect, got %v",err)}
}

func TestCancelRequestsInterruptsBlockedResponseAndLatchesUntilNextRoute(t *testing.T) {
    BeginRequests()
    defer BeginRequests()
    entered:=make(chan struct{})
    release:=make(chan struct{})
    target:=httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
        if r.URL.Path=="/blocked" {
            close(entered)
            select {case <-r.Context().Done():return;case <-release:}
        }
        w.WriteHeader(204)
    }))
    target.TLS=&tls.Config{ClientAuth:tls.RequestClientCert,MaxVersion:tls.VersionTLS12};target.StartTLS()
    defer target.Close()
    defer close(release)
    roots:=x509.NewCertPool();roots.AddCert(target.Certificate())
    transport:=&http.Transport{TLSClientConfig:&tls.Config{ServerName:"example.com",RootCAs:roots},DisableKeepAlives:true}
    defer transport.CloseIdleConnections()
    client:=&http.Client{Transport:transport,Timeout:30*time.Second}
    done:=make(chan error,1)
    go func(){_,err:=timedGet(client,target.URL+"/blocked",204);done<-err}()
    select{case <-entered:case <-time.After(3*time.Second):t.Fatal("request did not start")}
    CancelRequests()
    select{case err:=<-done:if err==nil{t.Fatal("cancelled request succeeded")};case <-time.After(time.Second):t.Fatal("cancellation waited for HTTP timeout")}
    if _,err:=timedGet(client,target.URL,204);err==nil{t.Fatal("new request ignored latched cancellation")}
    BeginRequests()
    if _,err:=timedGet(client,target.URL,204);err!=nil{t.Fatal("new route cannot probe",err)}
}

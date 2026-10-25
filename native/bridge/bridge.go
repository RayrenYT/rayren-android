// Package rayrencore is the narrow, non-logging Android binding for Rayren.
package rayrencore

import (
    "encoding/json"
    "errors"
    "io"
    "log"
    "net"
    "strconv"
    "sync"

    xray "github.com/2dust/AndroidLibXrayLite"
    rtc "github.com/openlibrecommunity/olcrtc/mobile"
    flux "openflux-mobile"
    fluxlog "github.com/p1neappleXpress/OpenFlux/utils"
)

// Protector is implemented by the sole Android VpnService.
type Protector interface { Protect(fd int) bool }

type callbacks struct{}
func (callbacks) Startup() int { return 0 }
func (callbacks) Shutdown() int { return 0 }
func (callbacks) OnEmitStatus(_ int, _ string) int { return 0 }

var coreMu sync.Mutex
var core *xray.CoreController
var runtime *rtc.Runtime
var fluxStartGate=make(chan struct{},1)

// Initialize disables native standard logging before any secret is supplied.
func Initialize(assetDir string) {
    log.SetOutput(io.Discard)
    fluxlog.SetOutput(io.Discard)
    xray.InitCoreEnv(assetDir, "")
    flux.SetDebugLevel(0)
}

func Version() string { return xray.CheckVersionX() }

func StartCore(config string) error {
    coreMu.Lock()
    defer coreMu.Unlock()
    if len(config) > 2<<20 { return errors.New("CORE_CONFIG_LIMIT") }
    if core != nil { return errors.New("CORE_ALREADY_RUNNING") }
    c := xray.NewCoreController(callbacks{})
    if err := c.StartLoop(config, 0); err != nil { _ = c.StopLoop(); return errors.New("CORE_CONFIG_OR_START_FAILED") }
    core = c
    return nil
}

func StopCore() {
    coreMu.Lock()
    defer coreMu.Unlock()
    if core != nil { _ = core.StopLoop(); core = nil }
}

func CoreRunning() bool {
    coreMu.Lock(); defer coreMu.Unlock()
    return core != nil && core.IsRunning
}

func Stats() string {
    coreMu.Lock(); defer coreMu.Unlock()
    if core == nil { return "{}" }
    return core.QueryAllOutboundTrafficStats()
}

type fluxConfig struct {
    Provider string `json:"provider"`
    URL string `json:"url"`
    Secret string `json:"secret"`
    Codec string `json:"codec"`
    Token string `json:"token"`
    UID string `json:"uid"`
    Session string `json:"session"`
}

func StartOpenFlux(config string, port int, user, password string) error {
    return runStartup(currentRequestContext(),fluxStartGate,func()error{return startOpenFlux(config,port,user,password)},func(){flux.StopProxy()})
}
func startOpenFlux(config string, port int, user, password string) error {
    var c fluxConfig
    if len(config)>65536 || json.Unmarshal([]byte(config), &c)!=nil { return errors.New("FLUX_CONFIG_INVALID") }
    if port<1024 || port>65535 || user=="" || password=="" { return errors.New("FLUX_LISTENER_INVALID") }
    flux.SetDebugLevel(0)
    addr := net.JoinHostPort("127.0.0.1",strconv.Itoa(port))
    var result string
    if c.Session!="" {
        result = flux.StartSessionProxy(c.Session,c.Secret,addr,user,password,"")
    } else {
        result = flux.StartProxy(c.Provider,c.URL,c.Secret,c.Codec,c.Token,c.UID,addr,user,password,"")
    }
    if result!="" { flux.StopProxy(); return errors.New("FLUX_START_FAILED") }
    return nil
}

func OpenFluxReady() bool { return flux.ProxyIsConnected() }
func StopOpenFlux() { flux.StopProxy() }

type rtcConfig struct {
    Provider string `json:"provider"`
    Transport string `json:"transport"`
    Room string `json:"room"`
    Key string `json:"key"`
    DNS string `json:"dns"`
    Device string `json:"device"`
}

func StartRtc(config string, port int, user, password string, p Protector) error {
    var c rtcConfig
    if len(config)>65536 || json.Unmarshal([]byte(config), &c)!=nil || p==nil { return errors.New("RTC_CONFIG_INVALID") }
    if runtime!=nil { return errors.New("RTC_ALREADY_RUNNING") }
    r := rtc.New()
    r.SetProtector(p)
    r.SetDebug(false)
    for _,err := range []error{r.SetProvider(c.Provider),r.SetTransport(c.Transport),r.SetRoom(c.Room),r.SetKey(c.Key),r.SetSocksListenHost("127.0.0.1"),r.SetSocksPort(port),r.SetSocksCredentials(user,password)} {
        if err!=nil { return errors.New("RTC_CONFIG_INVALID") }
    }
    if c.DNS!="" { if r.SetDNS(c.DNS)!=nil { return errors.New("RTC_DNS_INVALID") } }
    r.SetDeviceID(c.Device)
    if r.Start()!=nil { _=r.Stop(5000); return errors.New("RTC_START_FAILED") }
    runtime=r
    return nil
}

func RtcReady() bool { return runtime!=nil && runtime.IsRunning() && runtime.State()=="running" }
func StopRtc() { if runtime!=nil { _=runtime.Stop(5000); runtime=nil } }


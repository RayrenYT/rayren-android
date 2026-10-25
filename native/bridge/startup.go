package rayrencore

import "context"

// Startup may use a provider API without a cancellation hook. Release the JNI
// caller immediately on cancellation, but retain exclusive ownership until the
// old startup returns and its resources are stopped. A late result is never reused.
func runStartup(ctx context.Context, gate chan struct{}, start func() error, stop func()) error {
    select {case gate<-struct{}{}:case <-ctx.Done():return ctx.Err()}
    if err:=ctx.Err();err!=nil { <-gate;return err }
    done:=make(chan error,1)
    go func(){
        defer func(){<-gate}()
        err:=start()
        if ctx.Err()!=nil {stop();err=ctx.Err()}
        done<-err
    }()
    select {case err:=<-done:return err;case <-ctx.Done():return ctx.Err()}
}

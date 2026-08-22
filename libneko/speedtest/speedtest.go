package speedtest

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptrace"
	"time"
)

const UrlTestStandard_RTT = 0
const UrlTestStandard_Handshake = 1
const UrlTestStandard_FisrtHandshake = 2

var errNoRedir = errors.New("no redir")

func UrlTest(client *http.Client, link string, timeout int32, standard int) (int32, error) {
	if client == nil {
		return 0, fmt.Errorf("no client")
	}
	defer client.CloseIdleConnections()

	client.CheckRedirect = func(req *http.Request, via []*http.Request) error {
		return errNoRedir
	}

	var time_start time.Time
	var hsk_end time.Time
	var time_end time.Time
	var times int

	switch standard {
	case UrlTestStandard_FisrtHandshake:
		times = 1
	case UrlTestStandard_Handshake:
		times = 2
		rt := client.Transport.(*http.Transport)
		rt.DisableKeepAlives = true
	case UrlTestStandard_RTT:
		times = 2
	default:
		return 0, errors.New("unknown urltest standard")
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, "GET", link, nil)
	if err != nil {
		return 0, err
	}

	trace := &httptrace.ClientTrace{
		TLSHandshakeDone: func(cs tls.ConnectionState, err error) {
			hsk_end = time.Now()
		},
		GotFirstResponseByte: func() {
			time_end = time.Now()
		},
		WroteHeaders: func() {
			hsk_end = time.Now()
		},
	}
	req = req.WithContext(httptrace.WithClientTrace(req.Context(), trace))

	for i := 0; i < times; i++ {
		time_start = time.Now()
		resp, err := client.Do(req)
		if err != nil {
			if errors.Is(err, errNoRedir) {
				err = nil
			} else {
				return 0, err
			}
		}
		resp.Body.Close()
	}

	if time_end.IsZero() {
		time_end = time.Now()
	}

	if standard == UrlTestStandard_RTT {
		time_start = hsk_end
	}

	return int32(time_end.Sub(time_start).Milliseconds()), nil
}

func TcpPing(address string, timeout int32) (ms int32, err error) {
	startTime := time.Now()
	c, err := net.DialTimeout("tcp", address, time.Duration(timeout)*time.Millisecond)
	endTime := time.Now()
	if err == nil {
		ms = int32(endTime.Sub(startTime).Milliseconds())
		c.Close()
	}
	return
}

//go:build !linux

package protect_server

import "io"

func ServeProtect(path string, verbose bool, fwmark int, protectCtl func(fd int) error) (io.Closer, error) {
	return nil, nil
}

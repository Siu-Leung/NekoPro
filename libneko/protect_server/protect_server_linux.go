package protect_server

import (
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"reflect"
	"syscall"
	"time"
)

const (
	maxControlFds  = 16
	protectTimeout = 5 * time.Second
)

func closeAllFds(fds []int) {
	for _, fd := range fds {
		if fd >= 0 {
			_ = syscall.Close(fd)
		}
	}
}

func checkPeerUID(peerUID uint32, expectedUID int) error {
	if expectedUID < 0 || uint64(peerUID) != uint64(expectedUID) {
		return fmt.Errorf("unauthorized peer uid %d (want %d)", peerUID, expectedUID)
	}
	return nil
}

func verifyPeerCred(conn *net.UnixConn) error {
	rawConn, err := conn.SyscallConn()
	if err != nil {
		return err
	}
	var (
		ucred   *syscall.Ucred
		credErr error
	)
	if err := rawConn.Control(func(fd uintptr) {
		ucred, credErr = syscall.GetsockoptUcred(int(fd), syscall.SOL_SOCKET, syscall.SO_PEERCRED)
	}); err != nil {
		return err
	}
	if credErr != nil {
		return credErr
	}
	return checkPeerUID(ucred.Uid, os.Getuid())
}

func getOneFd(conn *net.UnixConn) (int, error) {
	var data [1]byte
	oob := make([]byte, syscall.CmsgSpace(4*maxControlFds))
	_, oobn, flags, _, err := conn.ReadMsgUnix(data[:], oob)
	if err != nil && !(err == io.EOF && oobn > 0) {
		return -1, err
	}

	msgs, parseErr := syscall.ParseSocketControlMessage(oob[:oobn])
	var allFds []int
	var rightsErr error
	for i := range msgs {
		if msgs[i].Header.Level == syscall.SOL_SOCKET && msgs[i].Header.Type == syscall.SCM_RIGHTS {
			fds, err := syscall.ParseUnixRights(&msgs[i])
			if err != nil && rightsErr == nil {
				rightsErr = err
			}
			allFds = append(allFds, fds...)
		}
	}
	if parseErr != nil {
		closeAllFds(allFds)
		return -1, parseErr
	}
	if rightsErr != nil {
		closeAllFds(allFds)
		return -1, rightsErr
	}
	if flags&syscall.MSG_CTRUNC != 0 {
		closeAllFds(allFds)
		return -1, fmt.Errorf("control message truncated")
	}
	if len(msgs) != 1 {
		closeAllFds(allFds)
		return -1, fmt.Errorf("invalid msgs count: %d", len(msgs))
	}
	if len(allFds) != 1 {
		closeAllFds(allFds)
		return -1, fmt.Errorf("invalid fds count: %d", len(allFds))
	}
	return allFds[0], nil
}

// GetFdFromConn get net.Conn's file descriptor.
func GetFdFromConn(l net.Conn) int {
	v := reflect.ValueOf(l)
	netFD := reflect.Indirect(reflect.Indirect(v).FieldByName("fd"))
	pfd := reflect.Indirect(netFD.FieldByName("pfd"))
	fd := int(pfd.FieldByName("Sysfd").Int())
	return fd
}

func ServeProtect(path string, verbose bool, fwmark int, protectCtl func(fd int)) (io.Closer, error) {
	if verbose {
		log.Println("ServeProtect", path, fwmark)
	}

	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	l, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(path, 0600); err != nil {
		_ = l.Close()
		_ = os.Remove(path)
		return nil, err
	}

	go func(ctl func(fd int)) {
		for {
			c, err := l.AcceptUnix()
			if err != nil {
				if verbose {
					log.Println("protect server accept:", err)
				}
				return
			}

			go func(conn *net.UnixConn) {
				defer conn.Close()
				_ = conn.SetDeadline(time.Now().Add(protectTimeout))

				if err := verifyPeerCred(conn); err != nil {
					if verbose {
						log.Println("protect server verifyPeerCred:", err)
					}
					_, _ = conn.Write([]byte{0})
					return
				}

				fd, err := getOneFd(conn)
				if err != nil {
					if verbose {
						log.Println("protect server getOneFd:", err)
					}
					_, _ = conn.Write([]byte{0})
					return
				}
				defer syscall.Close(fd)

				var protectErr error
				if ctl == nil {
					// linux
					if err := syscall.SetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_MARK, fwmark); err != nil {
						log.Println("protect server syscall.SetsockoptInt:", err)
						protectErr = err
					}
				} else {
					// android
					ctl(fd)
				}

				if protectErr == nil {
					_, _ = conn.Write([]byte{1})
				} else {
					_, _ = conn.Write([]byte{0})
				}
			}(c)
		}
	}(protectCtl)

	return l, nil
}

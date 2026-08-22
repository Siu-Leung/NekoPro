package anytls

import (
	"encoding/binary"
	"net"
	"reflect"
	"strings"
	"sync"
	"unsafe"

	"github.com/sagernet/sing/common"

	anytls "github.com/anytls/sing-anytls"
	"github.com/anytls/sing-anytls/session"
)

const (
	commandSettings = 4
	frameHeaderSize = 7
)

var (
	clientSessionField, _   = reflect.TypeOf(anytls.Client{}).FieldByName("sessionClient")
	streamSessionField, _   = reflect.TypeOf(session.Stream{}).FieldByName("sess")
	sessionConnLockField, _ = reflect.TypeOf(session.Session{}).FieldByName("connLock")
	sessionBufferField, _   = reflect.TypeOf(session.Session{}).FieldByName("buffer")
)

// metadataHookSupported reports whether the internal layout used by
// rewriteClientMetadata handles are still present. If the underlying
// sing-anytls version changes its struct layout, unsafe access must be
// disabled rather than silently reading the wrong memory.
func metadataHookSupported() bool {
	return clientSessionField.Index != nil &&
		streamSessionField.Index != nil &&
		sessionConnLockField.Index != nil &&
		sessionBufferField.Index != nil
}

func sessionClientOf(client *anytls.Client) *session.Client {
	if !metadataHookSupported() {
		return nil
	}
	return *(**session.Client)(unsafe.Add(unsafe.Pointer(client), clientSessionField.Offset))
}

func (h *Outbound) rewriteClientMetadata(conn net.Conn) {
	if !metadataHookSupported() {
		return
	}
	sess := *(**session.Session)(unsafe.Add(unsafe.Pointer(conn.(*session.Stream)), streamSessionField.Offset))
	connLock := (*sync.Mutex)(unsafe.Add(unsafe.Pointer(sess), sessionConnLockField.Offset))
	bufferPointer := (*[]byte)(unsafe.Add(unsafe.Pointer(sess), sessionBufferField.Offset))
	connLock.Lock()
	defer connLock.Unlock()
	buffer := *bufferPointer
	offset := 0
	for offset+frameHeaderSize <= len(buffer) {
		dataLength := int(binary.BigEndian.Uint16(buffer[offset+5 : offset+7]))
		frameEnd := offset + frameHeaderSize + dataLength
		if frameEnd > len(buffer) {
			return
		}
		if buffer[offset] == commandSettings {
			data := []byte(strings.Join(common.Map(strings.Split(string(buffer[offset+frameHeaderSize:frameEnd]), "\n"), func(line string) string {
				if strings.HasPrefix(line, "client=") {
					return "client=" + h.clientMetadata
				}
				return line
			}), "\n"))
			newBuffer := make([]byte, 0, offset+frameHeaderSize+len(data)+len(buffer)-frameEnd)
			newBuffer = append(newBuffer, buffer[:offset+5]...)
			newBuffer = binary.BigEndian.AppendUint16(newBuffer, uint16(len(data)))
			newBuffer = append(newBuffer, data...)
			newBuffer = append(newBuffer, buffer[frameEnd:]...)
			*bufferPointer = newBuffer
			return
		}
		offset = frameEnd
	}
}

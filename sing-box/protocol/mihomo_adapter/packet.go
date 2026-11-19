package mihomo_adapter

import (
	"net"

	C "github.com/metacubex/mihomo/constant"

	"github.com/sagernet/sing/common/bufio"
	M "github.com/sagernet/sing/common/metadata"
)

// WrapResolvedPacketConn adapts a packet connection returned by a mihomo
// ListenPacketContext call for sing-box. mihomo resolves domain destinations
// into metadata.DstIP and the returned connection only accepts IP addresses,
// so domain destinations are translated to the resolved address on write and
// back to the original domain on read.
func WrapResolvedPacketConn(pc net.PacketConn, metadata *C.Metadata, destination M.Socksaddr) net.PacketConn {
	if pc == nil || metadata == nil || !destination.IsDomain() || !metadata.DstIP.IsValid() {
		return pc
	}
	resolved := M.SocksaddrFrom(metadata.DstIP.Unmap(), destination.Port)
	return bufio.NewNATPacketConn(bufio.NewPacketConn(pc), resolved, destination)
}

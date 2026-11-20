package outbound

import "testing"

func TestMieruClientProfileMTU(t *testing.T) {
	for _, tc := range []struct {
		transport string
		mtu       int32
		want      int32
	}{
		{"UDP", 1350, 1350},
		{"UDP", 0, 0},
		{"TCP", 1350, 0},
	} {
		cfg, err := buildMieruClientConfig(MieruOption{
			Name: "test", Server: "127.0.0.1", Port: 1080,
			Transport: tc.transport, UserName: "test", Password: "test", MTU: tc.mtu,
		})
		if err != nil {
			t.Fatal(err)
		}
		if got := cfg.Profile.GetMtu(); got != tc.want {
			t.Errorf("%s MTU %d: got %d, want %d", tc.transport, tc.mtu, got, tc.want)
		}
	}
}

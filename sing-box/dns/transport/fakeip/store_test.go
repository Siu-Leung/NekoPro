package fakeip

import (
	"context"
	"net/netip"
	"sync"
	"testing"
)

func TestStoreConcurrentCreateAllocatesUniqueReversibleAddresses(t *testing.T) {
	store := NewStore(context.Background(), nil, netip.MustParsePrefix("198.18.0.0/16"), netip.MustParsePrefix("2001:db8:1::/112"))
	if err := store.Start(); err != nil {
		t.Fatal(err)
	}
	const count = 1000
	addresses := make(chan netip.Addr, count)
	var wg sync.WaitGroup
	for i := 0; i < count; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			address, err := store.Create("domain-"+netip.AddrFrom4([4]byte{10, byte(i >> 8), byte(i), 1}).String(), false)
			if err != nil {
				t.Errorf("create: %v", err)
				return
			}
			addresses <- address
		}(i)
	}
	wg.Wait()
	close(addresses)
	seen := make(map[netip.Addr]bool, count)
	for address := range addresses {
		if seen[address] {
			t.Fatalf("duplicate fake IP %s", address)
		}
		seen[address] = true
		domain, ok := store.Lookup(address)
		if !ok || domain == "" {
			t.Fatalf("address %s is not reversible", address)
		}
	}
	if len(seen) != count {
		t.Fatalf("got %d addresses, want %d", len(seen), count)
	}
}

func TestStoreResetAndCloseAreSynchronizedWithCreate(t *testing.T) {
	store := NewStore(context.Background(), nil, netip.MustParsePrefix("198.18.0.0/24"), netip.Prefix{})
	if err := store.Start(); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			_, _ = store.Create("d"+netip.AddrFrom4([4]byte{10, 0, 0, byte(i)}).String(), false)
		}(i)
	}
	wg.Add(2)
	go func() { defer wg.Done(); _ = store.Reset() }()
	go func() { defer wg.Done(); _ = store.Close() }()
	wg.Wait()
}

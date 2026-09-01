package wireguard

import (
	"testing"
)

func TestAllocateFreeIP_StandardSubnet(t *testing.T) {
	subnet := "10.8.0.0/24"
	usedIPs := make(map[string]bool)

	// First allocation should be 10.8.0.2 (.0 is network, .1 is server gateway)
	ip1, err := AllocateFreeIP(subnet, usedIPs)
	if err != nil {
		t.Fatalf("AllocateFreeIP failed: %v", err)
	}
	if ip1.String() != "10.8.0.2" {
		t.Errorf("expected 10.8.0.2, got %s", ip1.String())
	}

	// Mark .2 as used, next should be .3
	usedIPs["10.8.0.2"] = true
	ip2, err := AllocateFreeIP(subnet, usedIPs)
	if err != nil {
		t.Fatalf("AllocateFreeIP failed: %v", err)
	}
	if ip2.String() != "10.8.0.3" {
		t.Errorf("expected 10.8.0.3, got %s", ip2.String())
	}
}

func TestAllocateFreeIP_SkipGaps(t *testing.T) {
	subnet := "10.9.0.0/24"
	usedIPs := map[string]bool{
		"10.9.0.2": true,
		"10.9.0.3": true,
		"10.9.0.5": true,
	}

	// Should pick .4
	ip, err := AllocateFreeIP(subnet, usedIPs)
	if err != nil {
		t.Fatalf("AllocateFreeIP failed: %v", err)
	}
	if ip.String() != "10.9.0.4" {
		t.Errorf("expected 10.9.0.4, got %s", ip.String())
	}
}

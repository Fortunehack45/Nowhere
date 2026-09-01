package gameboost

import (
	"testing"
)

func TestSupportedGamesCatalog(t *testing.T) {
	games := SupportedGames()
	if len(games) == 0 {
		t.Fatal("expected non-empty games catalog")
	}

	for _, g := range games {
		if g.ID == "" || g.Name == "" || len(g.ServerHubs) == 0 {
			t.Errorf("invalid game definition: %+v", g)
		}
		for _, hub := range g.ServerHubs {
			if hub.PreferredNodeID == "" || hub.EstimatedPingMs <= 0 {
				t.Errorf("invalid server hub in game %s: %+v", g.ID, hub)
			}
		}
	}
}

func TestFindGameByID(t *testing.T) {
	game, ok := FindGameByID("cod_mobile")
	if !ok {
		t.Fatal("expected to find cod_mobile")
	}
	if game.Name != "Call of Duty: Mobile / Warzone" {
		t.Errorf("unexpected game name: %s", game.Name)
	}

	_, ok = FindGameByID("non_existent_game")
	if ok {
		t.Error("expected false for non-existent game")
	}
}

package config

import (
	"os"
	"testing"
	"time"
)

func TestLoadFromEnv_Valid(t *testing.T) {
	os.Setenv("ANDROID_PACKAGE_NAME", "com.nowhere.gps.locationchanger")
	os.Setenv("GOOGLE_PLAY_PRODUCT_IDS", "nowhere_premium, nowhere_premium_yearly")
	os.Setenv("PORT", "9090")
	os.Setenv("MAX_REQUEST_BODY_BYTES", "32768")
	os.Setenv("RATE_LIMIT_RPS", "15.5")
	os.Setenv("RATE_LIMIT_BURST", "30")
	os.Setenv("REQUEST_TIMEOUT_SECONDS", "5")
	os.Setenv("APP_ENV", "production")
	defer func() {
		os.Unsetenv("ANDROID_PACKAGE_NAME")
		os.Unsetenv("GOOGLE_PLAY_PRODUCT_IDS")
		os.Unsetenv("PORT")
		os.Unsetenv("MAX_REQUEST_BODY_BYTES")
		os.Unsetenv("RATE_LIMIT_RPS")
		os.Unsetenv("RATE_LIMIT_BURST")
		os.Unsetenv("REQUEST_TIMEOUT_SECONDS")
		os.Unsetenv("APP_ENV")
	}()

	cfg, err := LoadFromEnv()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if cfg.AndroidPackageName != "com.nowhere.gps.locationchanger" {
		t.Errorf("expected package com.nowhere.gps.locationchanger, got %s", cfg.AndroidPackageName)
	}
	if !cfg.IsProductAllowed("nowhere_premium") {
		t.Errorf("expected nowhere_premium to be allowed")
	}
	if !cfg.IsProductAllowed("nowhere_premium_yearly") {
		t.Errorf("expected nowhere_premium_yearly to be allowed")
	}
	if cfg.IsProductAllowed("unknown_product") {
		t.Errorf("expected unknown_product to be disallowed")
	}
	if cfg.Port != "9090" {
		t.Errorf("expected port 9090, got %s", cfg.Port)
	}
	if cfg.MaxRequestBodyBytes != 32768 {
		t.Errorf("expected max body 32768, got %d", cfg.MaxRequestBodyBytes)
	}
	if cfg.RateLimitRPS != 15.5 {
		t.Errorf("expected RPS 15.5, got %f", cfg.RateLimitRPS)
	}
	if cfg.RateLimitBurst != 30 {
		t.Errorf("expected burst 30, got %d", cfg.RateLimitBurst)
	}
	if cfg.RequestTimeout != 5*time.Second {
		t.Errorf("expected timeout 5s, got %v", cfg.RequestTimeout)
	}
	if cfg.Address() != "0.0.0.0:9090" {
		t.Errorf("expected address 0.0.0.0:9090, got %s", cfg.Address())
	}
}

func TestLoadFromEnv_MissingPackageName(t *testing.T) {
	os.Unsetenv("ANDROID_PACKAGE_NAME")
	os.Setenv("GOOGLE_PLAY_PRODUCT_IDS", "nowhere_premium")
	defer os.Unsetenv("GOOGLE_PLAY_PRODUCT_IDS")

	_, err := LoadFromEnv()
	if err == nil {
		t.Fatal("expected error for missing ANDROID_PACKAGE_NAME, got nil")
	}
}

func TestLoadFromEnv_MissingProductIDs(t *testing.T) {
	os.Setenv("ANDROID_PACKAGE_NAME", "com.nowhere.gps.locationchanger")
	os.Unsetenv("GOOGLE_PLAY_PRODUCT_IDS")
	defer os.Unsetenv("ANDROID_PACKAGE_NAME")

	_, err := LoadFromEnv()
	if err == nil {
		t.Fatal("expected error for missing GOOGLE_PLAY_PRODUCT_IDS, got nil")
	}
}

func TestLoadFromEnv_Defaults(t *testing.T) {
	os.Setenv("ANDROID_PACKAGE_NAME", "com.nowhere.gps.locationchanger")
	os.Setenv("GOOGLE_PLAY_PRODUCT_IDS", "nowhere_premium")
	os.Unsetenv("PORT")
	os.Unsetenv("MAX_REQUEST_BODY_BYTES")
	os.Unsetenv("RATE_LIMIT_RPS")
	os.Unsetenv("RATE_LIMIT_BURST")
	os.Unsetenv("REQUEST_TIMEOUT_SECONDS")
	defer func() {
		os.Unsetenv("ANDROID_PACKAGE_NAME")
		os.Unsetenv("GOOGLE_PLAY_PRODUCT_IDS")
	}()

	cfg, err := LoadFromEnv()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if cfg.Port != "8080" {
		t.Errorf("expected default port 8080, got %s", cfg.Port)
	}
	if cfg.MaxRequestBodyBytes != 16384 {
		t.Errorf("expected default max body 16384, got %d", cfg.MaxRequestBodyBytes)
	}
	if cfg.RateLimitRPS != 10.0 {
		t.Errorf("expected default RPS 10.0, got %f", cfg.RateLimitRPS)
	}
	if cfg.RateLimitBurst != 20 {
		t.Errorf("expected default burst 20, got %d", cfg.RateLimitBurst)
	}
	if cfg.RequestTimeout != 10*time.Second {
		t.Errorf("expected default timeout 10s, got %v", cfg.RequestTimeout)
	}
}

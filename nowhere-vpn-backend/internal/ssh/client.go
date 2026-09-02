package ssh

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
	"nowhere-vpn-backend/internal/config"
)

// ClientPool manages pooled, authenticated SSH connections to WireGuard VPS nodes.
type ClientPool struct {
	mu             sync.Mutex
	clients        map[string]*ssh.Client
	defaultKeyPath string
	timeout        time.Duration
	keySigners     map[string]ssh.Signer
}

// NewClientPool initializes a new SSH client pool.
func NewClientPool(defaultKeyPath string, timeout time.Duration) *ClientPool {
	if timeout == 0 {
		timeout = 10 * time.Second
	}
	return &ClientPool{
		clients:        make(map[string]*ssh.Client),
		defaultKeyPath: defaultKeyPath,
		timeout:        timeout,
		keySigners:     make(map[string]ssh.Signer),
	}
}

func (p *ClientPool) getSigner(keyPath string) (ssh.Signer, error) {
	if keyPath == "" {
		keyPath = p.defaultKeyPath
	}
	if keyPath == "" {
		return nil, errors.New("no SSH private key path provided")
	}

	if signer, ok := p.keySigners[keyPath]; ok {
		return signer, nil
	}

	keyBytes, err := os.ReadFile(keyPath)
	if err != nil {
		return nil, fmt.Errorf("failed to read SSH private key file %s: %w", keyPath, err)
	}

	signer, err := ssh.ParsePrivateKey(keyBytes)
	if err != nil {
		return nil, fmt.Errorf("failed to parse private key %s: %w", keyPath, err)
	}

	p.keySigners[keyPath] = signer
	return signer, nil
}

// GetClient retrieves or creates an active SSH client connection to the specified node.
func (p *ClientPool) GetClient(node config.Node) (*ssh.Client, error) {
	p.mu.Lock()
	defer p.mu.Unlock()

	addr := fmt.Sprintf("%s:%d", node.SSHHost, node.SSHPort)
	if client, ok := p.clients[addr]; ok {
		// Test if connection is still alive via keepalive request
		_, _, err := client.SendRequest("keepalive@openssh.com", true, nil)
		if err == nil {
			return client, nil
		}
		// Connection is stale or dead, close and recreate
		client.Close()
		delete(p.clients, addr)
	}

	signer, err := p.getSigner(node.SSHKeyPath)
	if err != nil {
		return nil, err
	}

	user := node.SSHUser
	if user == "" {
		user = "deploy"
	}

	sshConfig := &ssh.ClientConfig{
		User: user,
		Auth: []ssh.AuthMethod{
			ssh.PublicKeys(signer),
		},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(), // Node IPs are pre-configured in nodes.yaml
		Timeout:         p.timeout,
	}

	conn, err := net.DialTimeout("tcp", addr, p.timeout)
	if err != nil {
		return nil, fmt.Errorf("failed to dial SSH on %s: %w", addr, err)
	}

	sshConn, chans, reqs, err := ssh.NewClientConn(conn, addr, sshConfig)
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("failed SSH handshake with %s: %w", addr, err)
	}

	client := ssh.NewClient(sshConn, chans, reqs)
	p.clients[addr] = client
	return client, nil
}

// Run executes a command locally (if host is localhost/127.0.0.1) or remotely via SSH on the target node.
func (p *ClientPool) Run(ctx context.Context, node config.Node, command string) (string, error) {
	// If the node is on the local machine or localhost, execute directly via shell
	if node.SSHHost == "127.0.0.1" || node.SSHHost == "localhost" || node.SSHHost == "" {
		execCmd := command
		if strings.HasPrefix(execCmd, "sudo ") {
			if _, err := exec.LookPath("sudo"); err != nil {
				execCmd = strings.TrimPrefix(execCmd, "sudo ")
			}
		}
		cmd := exec.CommandContext(ctx, "sh", "-c", execCmd)
		var stdoutBuf, stderrBuf bytes.Buffer
		cmd.Stdout = &stdoutBuf
		cmd.Stderr = &stderrBuf
		if err := cmd.Run(); err != nil {
			// Non-fatal if command warned
			return stdoutBuf.String(), fmt.Errorf("local command [%s] failed: %w (stderr: %s)", execCmd, err, stderrBuf.String())
		}
		return stdoutBuf.String(), nil
	}

	client, err := p.GetClient(node)
	if err != nil {
		return "", fmt.Errorf("failed connecting to remote node %s (%s:%d): %w", node.ID, node.SSHHost, node.SSHPort, err)
	}

	session, err := client.NewSession()
	if err != nil {
		// Attempt reconnect once if session creation failed
		p.mu.Lock()
		addr := fmt.Sprintf("%s:%d", node.SSHHost, node.SSHPort)
		delete(p.clients, addr)
		p.mu.Unlock()

		client, err = p.GetClient(node)
		if err != nil {
			return "", err
		}
		session, err = client.NewSession()
		if err != nil {
			return "", fmt.Errorf("failed to create SSH session on %s: %w", node.SSHHost, err)
		}
	}
	defer session.Close()

	var stdoutBuf, stderrBuf bytes.Buffer
	session.Stdout = &stdoutBuf
	session.Stderr = &stderrBuf

	errChan := make(chan error, 1)
	go func() {
		errChan <- session.Run(command)
	}()

	select {
	case <-ctx.Done():
		session.Signal(ssh.SIGKILL)
		return "", ctx.Err()
	case err := <-errChan:
		if err != nil {
			return stdoutBuf.String(), fmt.Errorf("remote command [%s] failed on %s: %w (stderr: %s)", command, node.ID, err, stderrBuf.String())
		}
		return stdoutBuf.String(), nil
	}
}

// Close closes all pooled SSH connections.
func (p *ClientPool) Close() {
	p.mu.Lock()
	defer p.mu.Unlock()

	for addr, client := range p.clients {
		client.Close()
		delete(p.clients, addr)
	}
}

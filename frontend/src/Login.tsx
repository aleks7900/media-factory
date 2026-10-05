import React, { useState } from 'react';
import { Lock, User, Eye, EyeOff, ShieldAlert, Sparkles, ArrowRight, Loader2 } from 'lucide-react';
import { login, type AuthUser } from './api';

interface LoginProps {
  onLoginSuccess: (user: AuthUser) => void;
}

export function Login({ onLoginSuccess }: LoginProps) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username.trim() || !password) {
      setError('Please enter both username/email and password.');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const user = await login(username.trim(), password);
      onLoginSuccess(user);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Invalid credentials. Please verify your admin password.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-container">
      <div className="login-backdrop">
        <div className="login-glow login-glow-1" />
        <div className="login-glow login-glow-2" />
      </div>

      <div className="login-card">
        <div className="login-header">
          <div className="login-brand">
            <span className="brand-mark">
              <Sparkles size={24} />
            </span>
            <span className="brand-title">MEDIA FACTORY</span>
            <span className="login-badge">RESTRICTED</span>
          </div>
          <h2>Studio Administrator</h2>
          <p>Sign in to manage visual pipelines, review assets, and control production.</p>
        </div>

        {error && (
          <div role="alert" className="login-error">
            <ShieldAlert size={18} className="login-error-icon" />
            <div className="login-error-text">{error}</div>
          </div>
        )}

        <form onSubmit={handleSubmit} className="login-form">
          <div className="login-field">
            <label htmlFor="login-username">Username or Email</label>
            <div className="login-input-wrap">
              <User size={16} className="login-field-icon" />
              <input
                id="login-username"
                name="username"
                type="text"
                autoComplete="username"
                autoFocus
                required
                placeholder="admin or admin@mediafactory.local"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                disabled={loading}
              />
            </div>
          </div>

          <div className="login-field">
            <div className="login-field-header">
              <label htmlFor="login-password">Password</label>
            </div>
            <div className="login-input-wrap">
              <Lock size={16} className="login-field-icon" />
              <input
                id="login-password"
                name="password"
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                required
                placeholder="Enter admin password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                disabled={loading}
              />
              <button
                type="button"
                className="password-toggle"
                onClick={() => setShowPassword(!showPassword)}
                tabIndex={-1}
                aria-label={showPassword ? 'Hide password' : 'Show password'}
              >
                {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
          </div>

          <button
            type="submit"
            className="login-submit-btn primary"
            disabled={loading}
          >
            {loading ? (
              <>
                <Loader2 size={16} className="login-spinner" />
                <span>Authenticating...</span>
              </>
            ) : (
              <>
                <span>Sign In to Studio</span>
                <ArrowRight size={16} />
              </>
            )}
          </button>
        </form>

        <div className="login-footer">
          <div className="security-notice">
            <span className="security-dot" />
            <span>Encrypted JWT + HttpOnly SameSite Session</span>
          </div>
        </div>
      </div>
    </div>
  );
}

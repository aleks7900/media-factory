import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Login } from './Login';
import * as apiModule from './api';

describe('Login Component', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('renders login form with username, password, and submit button', () => {
    render(<Login onLoginSuccess={vi.fn()} />);

    expect(screen.getByText('MEDIA FACTORY')).toBeInTheDocument();
    expect(screen.getByText('Studio Administrator')).toBeInTheDocument();
    expect(screen.getByLabelText(/Username or Email/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Password/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Sign In to Studio/i })).toBeInTheDocument();
  });

  it('submits credentials and calls onLoginSuccess on valid response', async () => {
    const onLoginSuccess = vi.fn();
    const mockUser = { username: 'admin', role: 'ROLE_ADMIN', token: 'jwt-123' };
    vi.spyOn(apiModule, 'login').mockResolvedValue(mockUser);

    render(<Login onLoginSuccess={onLoginSuccess} />);

    await userEvent.type(screen.getByLabelText(/Username or Email/i), 'admin');
    await userEvent.type(screen.getByLabelText(/^Password/i), 'admin123');
    await userEvent.click(screen.getByRole('button', { name: /Sign In to Studio/i }));

    await waitFor(() => {
      expect(apiModule.login).toHaveBeenCalledWith('admin', 'admin123');
      expect(onLoginSuccess).toHaveBeenCalledWith(mockUser);
    });
  });

  it('displays error message when login fails', async () => {
    vi.spyOn(apiModule, 'login').mockRejectedValue(new Error('Invalid username or password'));

    render(<Login onLoginSuccess={vi.fn()} />);

    await userEvent.type(screen.getByLabelText(/Username or Email/i), 'admin');
    await userEvent.type(screen.getByLabelText(/^Password/i), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: /Sign In to Studio/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password');
  });

  it('toggles password visibility between password and text', async () => {
    render(<Login onLoginSuccess={vi.fn()} />);

    const passwordInput = screen.getByLabelText(/^Password/i);
    expect(passwordInput).toHaveAttribute('type', 'password');

    const toggleBtn = screen.getByRole('button', { name: /Show password/i });
    await userEvent.click(toggleBtn);
    expect(passwordInput).toHaveAttribute('type', 'text');

    await userEvent.click(screen.getByRole('button', { name: /Hide password/i }));
    expect(passwordInput).toHaveAttribute('type', 'password');
  });
});

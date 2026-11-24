import { useEffect, useState, type FormEvent } from 'react';
import { Eye, EyeOff, Loader2, LogIn, Sparkles, UserPlus } from 'lucide-react';
import { authApi } from '../api/auth';
import { getErrorMessage } from '../api/request';
import { useTheme } from '../hooks/useTheme';
import type { UserSession } from '../types/auth';

interface LoginPageProps {
  onLogin: (session: UserSession) => void;
}

type AuthMode = 'login' | 'register';

const inputClass = 'dark-input min-h-12 w-full rounded-xl px-3 outline-none';

export default function LoginPage({ onLogin }: LoginPageProps) {
  useTheme();
  const [registrationEnabled, setRegistrationEnabled] = useState(false);
  const [mode, setMode] = useState<AuthMode>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    authApi.config()
      .then(config => {
        if (active) {
          setRegistrationEnabled(Boolean(config?.registrationEnabled));
        }
      })
      .catch(() => {
        if (active) {
          setRegistrationEnabled(false);
        }
      });
    return () => {
      active = false;
    };
  }, []);

  const isRegister = registrationEnabled && mode === 'register';

  function switchMode(nextMode: AuthMode) {
    if (nextMode === mode) return;
    setMode(nextMode);
    setError('');
    setConfirmPassword('');
    setShowConfirmPassword(false);
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    const trimmedUsername = username.trim();
    if (!trimmedUsername) {
      setError('请输入账号');
      return;
    }
    if (isRegister) {
      if (password.length < 8) {
        setError('密码至少需要 8 个字符');
        return;
      }
      if (password !== confirmPassword) {
        setError('两次输入的密码不一致');
        return;
      }
    }
    setSubmitting(true);
    setError('');
    try {
      const session = isRegister
        ? await authApi.register(trimmedUsername, password)
        : await authApi.login(trimmedUsername, password);
      onLogin(session);
    } catch (error) {
      setError(getErrorMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  const submitDisabled = submitting
    || !username.trim()
    || !password
    || (isRegister && !confirmPassword);

  return (
    <main className="grid min-h-dvh place-items-center px-5 py-12">
      <section aria-labelledby="login-title" className="w-full max-w-md">
        <div className="mb-8 flex items-center gap-3">
          <div className="grid size-11 place-items-center rounded-xl bg-primary-600 text-white">
            <Sparkles aria-hidden="true" className="size-5" />
          </div>
          <span className="font-display text-xl font-semibold tracking-tight">AI Interview</span>
        </div>
        <div className="rounded-2xl border border-slate-200 bg-white px-6 py-8
          shadow-lg shadow-primary-950/5 sm:px-9 dark:border-slate-700 dark:bg-slate-900"
        >
          <h1 id="login-title" className="text-2xl">
            {isRegister ? '注册面试工作台账号' : '登录面试工作台'}
          </h1>
          {registrationEnabled && (
            <div
              role="tablist"
              aria-label="认证方式"
              className="mt-5 grid grid-cols-2 rounded-xl bg-slate-100 p-1 dark:bg-slate-800"
            >
              <button
                type="button"
                role="tab"
                aria-selected={!isRegister}
                onClick={() => switchMode('login')}
                className={`flex items-center justify-center gap-1.5 rounded-lg py-2 text-sm font-medium transition-colors ${
                  !isRegister
                    ? 'bg-white text-slate-900 shadow-sm dark:bg-slate-700 dark:text-white'
                    : 'text-slate-600 hover:text-slate-900 dark:text-slate-400 dark:hover:text-white'
                }`}
              >
                <LogIn aria-hidden="true" className="size-4" />
                登录
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={isRegister}
                onClick={() => switchMode('register')}
                className={`flex items-center justify-center gap-1.5 rounded-lg py-2 text-sm font-medium transition-colors ${
                  isRegister
                    ? 'bg-white text-slate-900 shadow-sm dark:bg-slate-700 dark:text-white'
                    : 'text-slate-600 hover:text-slate-900 dark:text-slate-400 dark:hover:text-white'
                }`}
              >
                <UserPlus aria-hidden="true" className="size-4" />
                注册
              </button>
            </div>
          )}
          <form className="mt-7 space-y-5" noValidate onSubmit={handleSubmit}>
            <div>
              <label htmlFor="login-username" className="mb-2 block text-sm font-medium">账号</label>
              <input id="login-username" name="username" type="text" autoComplete="username"
                className={inputClass} required maxLength={64} value={username}
                onChange={event => setUsername(event.target.value)}
              />
            </div>
            <div>
              <label htmlFor="login-password" className="mb-2 block text-sm font-medium">密码</label>
              <div className="relative">
                <input id="login-password" name="password"
                  autoComplete={isRegister ? 'new-password' : 'current-password'}
                  type={showPassword ? 'text' : 'password'} className={`${inputClass} pr-12`}
                  required minLength={isRegister ? 8 : undefined} maxLength={256} value={password}
                  aria-describedby={isRegister ? 'login-password-hint' : undefined}
                  onChange={event => setPassword(event.target.value)}
                />
                <button type="button" aria-label={showPassword ? '隐藏密码' : '显示密码'}
                  onClick={() => setShowPassword(value => !value)}
                  className="absolute inset-y-1 right-1 grid w-10 place-items-center rounded-lg
                    text-slate-500 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
                >
                  {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                </button>
              </div>
              {isRegister && (
                <p id="login-password-hint" className="mt-1.5 text-xs text-slate-500 dark:text-slate-400">
                  密码至少 8 位
                </p>
              )}
            </div>
            {isRegister && (
              <div>
                <label htmlFor="login-confirm-password" className="mb-2 block text-sm font-medium">确认密码</label>
                <div className="relative">
                  <input id="login-confirm-password" name="confirmPassword"
                    autoComplete="new-password"
                    type={showConfirmPassword ? 'text' : 'password'} className={`${inputClass} pr-12`}
                    required minLength={8} maxLength={256} value={confirmPassword}
                    onChange={event => setConfirmPassword(event.target.value)}
                  />
                  <button type="button" aria-label={showConfirmPassword ? '隐藏确认密码' : '显示确认密码'}
                    onClick={() => setShowConfirmPassword(value => !value)}
                    className="absolute inset-y-1 right-1 grid w-10 place-items-center rounded-lg
                      text-slate-500 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
                  >
                    {showConfirmPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                  </button>
                </div>
              </div>
            )}
            {error && <p role="alert" className="text-sm text-red-700 dark:text-red-300">{error}</p>}
            <button type="submit" disabled={submitDisabled}
              className="flex min-h-12 w-full items-center justify-center gap-2 rounded-xl
                bg-primary-600 px-4 font-medium text-white hover:bg-primary-700 disabled:opacity-60"
            >
              {submitting && <Loader2 aria-hidden="true" className="size-4 animate-spin" />}
              {submitting ? (isRegister ? '注册中…' : '登录中…') : (isRegister ? '注册' : '登录')}
            </button>
          </form>
        </div>
      </section>
    </main>
  );
}

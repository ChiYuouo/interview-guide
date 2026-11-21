import { useState, type FormEvent } from 'react';
import { Eye, EyeOff, Loader2, Sparkles } from 'lucide-react';
import { authApi } from '../api/auth';
import { getErrorMessage } from '../api/request';
import { useTheme } from '../hooks/useTheme';
import type { UserSession } from '../types/auth';

interface LoginPageProps {
  onLogin: (session: UserSession) => void;
}

const inputClass = 'dark-input min-h-12 w-full rounded-xl px-3 outline-none';

export default function LoginPage({ onLogin }: LoginPageProps) {
  useTheme();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError('');
    try {
      onLogin(await authApi.login(username.trim(), password));
    } catch (error) {
      setError(getErrorMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

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
          <h1 id="login-title" className="text-2xl">登录面试工作台</h1>
          <form className="mt-7 space-y-5" onSubmit={handleSubmit}>
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
                <input id="login-password" name="password" autoComplete="current-password"
                  type={showPassword ? 'text' : 'password'} className={`${inputClass} pr-12`}
                  required maxLength={256} value={password}
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
            </div>
            {error && <p role="alert" className="text-sm text-red-700 dark:text-red-300">{error}</p>}
            <button type="submit" disabled={submitting || !username.trim() || !password}
              className="flex min-h-12 w-full items-center justify-center gap-2 rounded-xl
                bg-primary-600 px-4 font-medium text-white hover:bg-primary-700 disabled:opacity-60"
            >
              {submitting && <Loader2 aria-hidden="true" className="size-4 animate-spin" />}
              {submitting ? '登录中…' : '登录'}
            </button>
          </form>
        </div>
      </section>
    </main>
  );
}

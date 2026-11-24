import { request } from './request';
import type { AuthConfig, UserSession } from '../types/auth';

export const authApi = {
  config: () => request.get<AuthConfig>('/api/auth/config'),
  session: () => request.get<UserSession>('/api/auth/session'),
  login: (username: string, password: string) =>
    request.post<UserSession>('/api/auth/login', { username, password }),
  register: (username: string, password: string) =>
    request.post<UserSession>('/api/auth/register', { username, password }),
  logout: () => request.post<null>('/api/auth/logout'),
};

import { request } from './request';
import type { UserSession } from '../types/auth';

export const authApi = {
  session: () => request.get<UserSession>('/api/auth/session'),
  login: (username: string, password: string) =>
    request.post<UserSession>('/api/auth/login', { username, password }),
  logout: () => request.post<null>('/api/auth/logout'),
};

export interface UserSession {
  username: string;
  admin: boolean;
}

export interface CsrfToken {
  headerName: string;
  token: string;
}

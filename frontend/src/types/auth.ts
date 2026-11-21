export interface UserSession {
  username: string;
}

export interface CsrfToken {
  headerName: string;
  token: string;
}

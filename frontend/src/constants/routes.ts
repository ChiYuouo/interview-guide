export const ROUTES = {
  login: '/login',
  interviewSchedule: '/interview-schedule',
  interview: '/interview',
  interviewHub: '/interview-hub',
  interviewHistory: '/interviews',
  voiceInterview: '/voice-interview',
  interviewCreate: (requestId: string) => `/interview/create/${requestId}`,
  interviewSession: (sessionId: string) => `/interview/session/${sessionId}`,
  resumeUpload: '/upload',
  resumeHistory: '/history',
  knowledgebaseUpload: '/knowledgebase/upload',
} as const;

export const ROUTE_PATTERNS = {
  interviewCreate: 'interview/create/:requestId',
  interviewSession: 'interview/session/:activeSessionId',
  interviewLegacy: 'interview/:resumeId',
  interviewDetail: 'interviews/:sessionId',
  voiceInterviewEvaluation: 'voice-interview/:sessionId/evaluation',
} as const;

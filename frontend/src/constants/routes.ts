export const ROUTES = {
  login: '/login',
  settings: '/settings',
  interviewSchedule: '/interview-schedule',
  interview: '/interview',
  interviewHub: '/interview-hub',
  interviewHistory: '/interviews',
  voiceInterview: '/voice-interview',
  interviewCreate: (requestId: string) => `/interview/create/${requestId}`,
  interviewSession: (sessionId: string) => `/interview/session/${sessionId}`,
  resumeUpload: '/upload',
  resumeHistory: '/history',
  knowledgebase: '/knowledgebase',
  knowledgebaseUpload: '/knowledgebase/upload',
  knowledgebaseChat: '/knowledgebase/chat',
  knowledgebaseInterview: '/knowledgebase-interview',
} as const;

export const ROUTE_PATTERNS = {
  interviewCreate: 'interview/create/:requestId',
  interviewSession: 'interview/session/:activeSessionId',
  interviewLegacy: 'interview/:resumeId',
  interviewDetail: 'interviews/:sessionId',
  voiceInterviewEvaluation: 'voice-interview/:sessionId/evaluation',
  knowledgebaseInterviewQuestions: 'knowledgebase-interview/:knowledgeBaseId/questions',
  knowledgebaseInterviewHistory: 'knowledgebase-interview/:knowledgeBaseId/interviews',
  knowledgebaseInterviewDetail: 'knowledgebase-interview/:knowledgeBaseId/interviews/:sessionId',
  knowledgebaseInterviewSession: 'knowledgebase-interview/:sessionId',
} as const;

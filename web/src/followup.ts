export interface CareEpisode { id: number; patient: string; patientId: number; reference: string; kind: string; careDate: string; sourceVersion: number; departmentId: number }
export interface Choice { id: number; name: string }
export interface Directory {
  reviewers: Choice[]; owners: Choice[]; departments: Choice[]; syntheticDischarges: boolean; pdfEnabled: boolean
}
export interface Source { id: number; title: string; versionId: number; versionNumber: number; pageNumber: number | null; lineNumber: number; excerpt: string }
export interface SourceContext {
  target: Source
  lines: Pick<Source, 'id' | 'lineNumber' | 'excerpt'>[]
}
export interface DocumentVersion { id: number; title: string; versionId: number; versionNumber: number; filename: string; createdAt: string }
export interface Followup {
  id: number; episodeId: number; reference: string; patient: string; ownerId: number; ownerName: string; reviewerId: number; departmentId: number; status: string;
  version: number; needsReview: boolean; kind: string; title: string; instructions: string | null; dueAt: string;
  revisitFrom: string | null; revisitTo: string | null; revisitDepartmentId: number | null; revisitDepartment: string | null;
  manualReference: string | null; sources: Source[];
  feedback: { id: number; kind: string; detail: string; actor: string; createdAt: string; sourceEncounterId: number | null }[];
  requests: FollowupRequest[];
  replies: FollowupReply[];
  previous: RevisionComparison | null;
  resultUpdates: { stage: string; patientMessage: string | null; createdAt: string }[];
  reviews: { id: number; decision: string; reason: string | null; createdAt: string }[];
}
export interface RevisionComparison {
  version: number; title: string; kind: string; revisitDepartment: string | null; revisitFrom: string | null; revisitTo: string | null
  dueAt: string; instructions: string; manualReference: string | null; sources: Source[]
}
export interface FollowupRequest {
  id: number; category: string; patientNote: string | null; status: string; outcome: string | null
  publicReply: string | null; createdAt: string; startedAt: string | null; resolvedAt: string | null
  responseDueAt: string; nextUpdateAt: string | null; appointmentId: number | null
}
export interface DepartmentTemplate { id: number; name: string; kind: string; content: string }
export interface FollowupReply {
  id: number; requestId: number; outcome: string; publicReply: string; nextUpdateAt: string | null; createdAt: string
}
export const requestCategories: Record<string, string> = {
  REVISIT_ASSISTANCE: '复诊协助', COMPLETED: '已完成', DIFFICULTY: '遇到困难', QUESTION: '有疑问',
}
export const requestStates: Record<string, string> = {
  SUBMITTED: '已提交', IN_PROGRESS: '处理中', RESOLVED: '已处理',
}
export const requestOutcomes: Record<string, string> = {
  SLOTS_AVAILABLE: '已有可预约安排', NO_SLOTS: '仍无号源', REVIEW_NEEDED: '需要重新审核',
  BOOKED: '已成功预约', TASK_ENDED: '随访已结束', REPLIED: '医护已回复',
}
export interface Revisit { appointmentId: number; supersedesId: number | null; status: string; startsAt: string; doctor: string; department: string; encounterId: number | null; completedAt: string | null; needsReschedule: boolean; bookingChangesCloseAt: string }

export const followupStates: Record<string, string> = {
  DRAFT: '草稿', IN_REVIEW: '待审核', APPROVED: '审核通过', DISPATCHED: '已派发',
  PATIENT_REPLIED: '患者已反馈', VERIFIED: '医护已核实', CLOSED: '已结案', UNDER_CORRECTION: '资料待复核',
}

export const feedbackKinds: Record<string, string> = {
  PATIENT: '患者反馈', CONTACT: '人工联系', VERIFICATION: '医护核实', CLOSURE: '结案记录',
}

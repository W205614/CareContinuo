export interface CareEpisode { id: number; patient: string; patientId: number; reference: string; kind: string; careDate: string; sourceVersion: number }
export interface Choice { id: number; name: string }
export interface Directory { patients: Choice[]; reviewers: Choice[]; owners: Choice[] }
export interface Source { id: number; title: string; versionId: number; versionNumber: number; pageNumber: number | null; lineNumber: number; excerpt: string }
export interface DocumentVersion { id: number; title: string; versionId: number; versionNumber: number; filename: string; createdAt: string }
export interface Followup {
  id: number; episodeId: number; reference: string; patient: string; ownerId: number; reviewerId: number; status: string;
  version: number; needsReview: boolean; kind: string; title: string; instructions: string | null; dueAt: string;
  manualReference: string | null; sources: Source[];
  feedback: { id: number; kind: string; detail: string; actor: string; createdAt: string; sourceEncounterId: number | null }[];
  reviews: { id: number; decision: string; reason: string | null; createdAt: string }[];
}
export interface Revisit { appointmentId: number; supersedesId: number | null; status: string; startsAt: string; doctor: string; department: string; encounterId: number | null; completedAt: string | null }

export const followupStates: Record<string, string> = {
  DRAFT: '草稿', IN_REVIEW: '待审核', APPROVED: '审核通过', DISPATCHED: '已派发',
  PATIENT_REPLIED: '患者已反馈', VERIFIED: '医护已核实', CLOSED: '已结案', UNDER_CORRECTION: '资料待复核',
}

export const feedbackKinds: Record<string, string> = {
  PATIENT: '患者反馈', CONTACT: '人工联系', VERIFICATION: '医护核实', CLOSURE: '结案记录',
}

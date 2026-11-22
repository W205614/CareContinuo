export interface InboxMessage {
  id: number; objectId: number; kind: string; title: string; createdAt: string
  readAt: string | null; correctedAt: string | null; replacementId: number | null; followupRequestId: number | null; revisitRequestId: number | null; revisitAppointmentId: number | null
}

export function messageTarget(message: InboxMessage) {
  const type = message.kind.startsWith('FOLLOWUP_') ? 'followup' : 'appointment'
  return { path: '/workspace', query: {
    [type]: message.objectId,
    request: message.correctedAt ? undefined : message.followupRequestId ?? undefined,
    revisitAppointment: message.correctedAt ? undefined : message.revisitAppointmentId ?? undefined,
    progress: !message.correctedAt && message.kind === 'FOLLOWUP_RESULT_UPDATED' ? 'result' : undefined,
  } }
}

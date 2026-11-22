export interface Slot { id: number; doctorId: number; capacity: number; visitLocation: string | null; preparation: string | null; doctorName: string; department: string; departmentId: number; startsAt: string; endsAt: string; remaining: number; available: boolean; doctorInDepartment: boolean; stoppedAt: string | null; stopReason: string | null }
export interface Appointment {
  id: number; slotId: number; doctor: string; department: string; startsAt: string; endsAt: string
  status: string; patientName: string; stopReason: string | null; followupId: number | null
  visitLocation: string | null; preparation: string | null; checkInOpensAt: string; checkInClosesAt: string; bookingChangesCloseAt: string
}
export interface SlotPage { slots: Slot[]; nextCursor: number | null }
export interface RevisitSlotPage extends SlotPage { availability: 'AVAILABLE' | 'FULL' | 'NOT_RELEASED' | 'NO_MATCH' }
export interface RevisitFilters { date?: string; doctor?: string; period?: string }

export const noRevisitSlots = {
  AVAILABLE: '暂无可预约的复诊号源。',
  FULL: '符合当前复诊安排的号源已满。',
  NOT_RELEASED: '当前复诊安排内暂无开放号源。',
  NO_MATCH: '没有符合筛选条件的号源，可以清除筛选后再看。',
}

export function revisitQuery(appointmentId: number | undefined, filters: RevisitFilters, afterId?: number) {
  const query = new URLSearchParams()
  if (appointmentId) query.set('appointmentId', String(appointmentId))
  if (filters.date) query.set('date', filters.date)
  if (filters.doctor) query.set('doctor', filters.doctor)
  if (filters.period) query.set('period', filters.period)
  if (afterId) query.set('afterId', String(afterId))
  return query
}

export interface Schedule { departments: { id: number; name: string }[]; doctors: { id: number; displayName: string }[]; slots: Slot[] }

export const appointmentStates: Record<string, string> = {
  BOOKED: '已预约', CHECKED_IN: '已签到', IN_CONSULT: '已接诊', COMPLETED: '已完成',
  CANCELLED: '已取消', RESCHEDULED: '已改约', NO_SHOW: '未到诊', STOPPED: '停诊待处理',
}

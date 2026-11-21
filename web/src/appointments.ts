export interface Slot { id: number; doctorName: string; department: string; startsAt: string; endsAt: string; remaining: number; stoppedAt: string | null; stopReason: string | null }
export interface Schedule { departments: { id: number; name: string }[]; doctors: { id: number; displayName: string }[]; slots: Slot[] }

export const appointmentStates: Record<string, string> = {
  BOOKED: '已预约', CHECKED_IN: '已签到', IN_CONSULT: '已接诊', COMPLETED: '已完成',
  CANCELLED: '已取消', RESCHEDULED: '已改约', NO_SHOW: '未到诊', STOPPED: '停诊待处理',
}

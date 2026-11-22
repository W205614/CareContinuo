const dateTime = new Intl.DateTimeFormat('zh-CN', {
  dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Shanghai', hourCycle: 'h23',
})

const timeOfDay = new Intl.DateTimeFormat('zh-CN', {
  timeStyle: 'short', timeZone: 'Asia/Shanghai', hourCycle: 'h23',
})

const localDate = new Intl.DateTimeFormat('sv-SE', {
  year: 'numeric', month: '2-digit', day: '2-digit', timeZone: 'Asia/Shanghai',
})

export function shanghaiDate(value: string) {
  return localDate.format(new Date(value))
}

export function shanghaiTime(value: string) {
  return dateTime.format(new Date(value))
}

export function shanghaiTimeOnVisitDay(value: string, visitStart: string) {
  return shanghaiDate(value) === shanghaiDate(visitStart) ? timeOfDay.format(new Date(value)) : shanghaiTime(value)
}

export function toShanghaiInput(value: string) {
  return new Date(Date.parse(value) + 8 * 3_600_000).toISOString().slice(0, 16)
}

export function fromShanghaiInput(value: string) {
  return `${value}:00+08:00`
}

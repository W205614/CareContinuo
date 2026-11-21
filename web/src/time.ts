const dateTime = new Intl.DateTimeFormat('zh-CN', {
  dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Shanghai', hourCycle: 'h23',
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

export function fromShanghaiInput(value: string) {
  return `${value}:00+08:00`
}

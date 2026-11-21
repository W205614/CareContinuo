const dateTime = new Intl.DateTimeFormat('zh-CN', {
  dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Shanghai', hourCycle: 'h23',
})

export function shanghaiTime(value: string) {
  return dateTime.format(new Date(value))
}

export function fromShanghaiInput(value: string) {
  return `${value}:00+08:00`
}

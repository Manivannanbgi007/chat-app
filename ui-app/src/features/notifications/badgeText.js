// The text inside the bell's badge: the number, but never wider than "99+".
export function badgeText(count) {
  return count > 99 ? '99+' : String(count)
}

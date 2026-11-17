export function renderMarkdown(text: string): string {
  if (!text) return ''
  let result = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  result = result.replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang: string, code: string) => {
    const escapedLang = lang.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    return `<pre><code class="lang-${escapedLang}">${code.replace(/\n$/, '')}</code></pre>`
  })
  result = result.replace(/^### (.*)$/gm, '<h3>$1</h3>')
  result = result.replace(/^## (.*)$/gm, '<h2>$1</h2>')
  result = result.replace(/^# (.*)$/gm, '<h1>$1</h1>')
  result = result.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  result = result.replace(/\*(.+?)\*/g, '<em>$1</em>')
  result = result.replace(/`([^`]+)`/g, '<code>$1</code>')
  result = result.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>')
  result = result.replace(/(?:^|\n)((?:- .+\n?)+)/g, (match) => {
    const items = match.trim().split('\n').map((line) => '<li>' + line.replace(/^- /, '') + '</li>').join('')
    return '<ul>' + items + '</ul>'
  })
  result = result.split(/\n\n+/).map((paragraph) => {
    const trimmed = paragraph.trim()
    return trimmed ? '<p>' + trimmed.replace(/\n/g, '<br>') + '</p>' : ''
  }).join('')
  return result
}
import { randomBytes } from 'node:crypto'
import { existsSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const target = fileURLToPath(new URL('../.env', import.meta.url))
if (existsSync(target)) {
  console.log('.env already exists. Existing values were kept.')
} else {
  const content = [
    '# Local development only. Do not commit this file.',
    'DB_NAME=crypto_market_monitor',
    'DB_USER=crypto_app',
    'DB_HOST=127.0.0.1',
    'DB_PORT=3306',
    `DB_PASSWORD=${randomBytes(32).toString('hex')}`,
    `MYSQL_ROOT_PASSWORD=${randomBytes(32).toString('hex')}`,
    '',
  ].join('\n')
  writeFileSync(target, content, { flag: 'wx', mode: 0o600 })
  console.log('Created .env with generated local passwords. Passwords are not printed.')
}

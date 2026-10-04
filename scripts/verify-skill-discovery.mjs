import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdir, writeFile } from 'node:fs/promises';

const expected = ['research-trends', 'create-collection', 'run-qa', 'prepare-stock', 'create-wallpapers'];
const child = spawn(process.env.CODEX_EXECUTABLE || 'codex', ['app-server', '--stdio'], { stdio: ['pipe', 'pipe', 'pipe'] });
const pending = new Map();
let sequence = 0;
const timer = setTimeout(() => { child.kill(); console.error('Discovery timed out'); process.exitCode = 1; }, 45000);
child.stderr.resume(); // Never copy host configuration or credentials into evidence.
createInterface({ input: child.stdout }).on('line', line => {
  try { const message = JSON.parse(line); const callback = pending.get(message.id); if (callback) { pending.delete(message.id); callback(message); } } catch {}
});
const rpc = (method, params) => new Promise((resolve, reject) => {
  const id = ++sequence;
  pending.set(id, message => message.error ? reject(new Error(`RPC ${method} failed`)) : resolve(message.result));
  child.stdin.write(JSON.stringify({ id, method, params }) + '\n');
});
try {
  await rpc('initialize', { clientInfo: { name: 'media_factory_skill_verifier', version: '1.0.0' } });
  child.stdin.write(JSON.stringify({ method: 'initialized' }) + '\n');
  const result = await rpc('skills/list', { cwds: [process.cwd()], forceReload: true });
  const skills = result.data.flatMap(entry => entry.skills).filter(skill => expected.includes(skill.name));
  const evidence = { observedAt: new Date().toISOString(), mechanism: 'Codex app-server skills/list', skills: skills.map(({ name, path, enabled }) => ({ name, path, enabled })), missing: expected.filter(name => !skills.some(skill => skill.name === name && skill.enabled !== false)) };
  await mkdir('backend/build', { recursive: true });
  await writeFile('backend/build/task12-skill-discovery.json', JSON.stringify(evidence, null, 2));
  console.log(JSON.stringify(evidence, null, 2));
  if (evidence.missing.length) process.exitCode = 1;
} catch (error) { console.error(error.message); process.exitCode = 1; }
finally { clearTimeout(timer); child.kill(); }

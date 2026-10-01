import { useCallback, useEffect, useState } from 'react'

type Project = { id: number; name: string; baseUrl: string }
type Scenario = { id: number; projectId: number; name: string; definition: unknown }
type Endpoint = { step: string; avg: number; p95: number; p99: number; count: number; errorRate: number }
type Stats = {
  avg: number; min: number; max: number; p50: number; p90: number; p95: number; p99: number
  requests: number; rps: number; errorRate: number; iterations: number; vusMax: number
  endpoints: Endpoint[]; thresholds: { metric: string; expression: string; ok: boolean }[]
}
type Run = { id: number; scenarioId: number; status: string; passed: boolean | null; stats: Stats | null; logTail: string | null; createdAt: string }
type Cmp = { metric: string; a: number; b: number; deltaPct: number; verdict: string }

const DEMO_SCENARIO = {
  steps: [
    { id: 'login', request: { method: 'POST', path: '/anything/login', body: { email: 'user@example.com', token: 'abc-123' } },
      extract: [{ var: 'token', from: 'json', path: '$.json.token' }], assert: [{ type: 'status', equals: 200 }] },
    { think: { min: 1, max: 3 } },
    { id: 'list_orders', request: { method: 'GET', path: '/anything/orders', query: { page: '1' }, headers: { Authorization: 'Bearer {{token}}' } },
      assert: [{ type: 'status', equals: 200 }, { type: 'maxDuration', ms: 800 }] },
    { if: '{{token}}', then: [{ id: 'get_order', request: { method: 'GET', path: '/anything/orders/42' }, assert: [{ type: 'status', equals: 200 }] }] },
    { loop: { times: 2 }, steps: [{ id: 'health', request: { method: 'GET', path: '/status/200' } }] },
  ],
}

const TH = { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<800'] }
const PRESETS: Record<string, unknown> = {
  smoke: { load: { executor: 'constant-vus', vus: 1, duration: '30s' }, thresholds: TH },
  average: { load: { executor: 'ramping-vus', startVUs: 0, stages: [{ duration: '1m', target: 20 }, { duration: '3m', target: 20 }, { duration: '30s', target: 0 }] }, thresholds: TH },
  stress: { load: { executor: 'ramping-vus', startVUs: 0, stages: [{ duration: '2m', target: 50 }, { duration: '3m', target: 100 }, { duration: '3m', target: 200 }, { duration: '2m', target: 0 }] }, thresholds: TH },
  spike: { load: { executor: 'ramping-vus', startVUs: 5, stages: [{ duration: '30s', target: 5 }, { duration: '10s', target: 300 }, { duration: '1m', target: 300 }, { duration: '10s', target: 5 }, { duration: '1m', target: 5 }] }, thresholds: TH },
  soak: { load: { executor: 'constant-vus', vus: 30, duration: '30m' }, thresholds: TH },
  breakpoint: { load: { executor: 'ramping-arrival-rate', startRate: 10, timeUnit: '1s', preAllocatedVUs: 100, maxVUs: 2000, stages: [{ duration: '15m', target: 1000 }] }, thresholds: TH },
  'arrival-rate': { load: { executor: 'ramping-arrival-rate', startRate: 10, timeUnit: '1s', preAllocatedVUs: 50, maxVUs: 500, stages: [{ duration: '1m', target: 50 }, { duration: '3m', target: 50 }, { duration: '30s', target: 0 }] }, thresholds: TH },
}

async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const r = await fetch(url, { headers: { 'Content-Type': 'application/json' }, ...init })
  const t = await r.text()
  if (!r.ok) { let m = r.statusText; try { m = JSON.parse(t).error ?? m } catch { /* not json */ } throw new Error(m) }
  return (t ? JSON.parse(t) : null) as T
}

const ms = (v: number) => (v >= 1000 ? (v / 1000).toFixed(2) + ' s' : v.toFixed(0) + ' ms')
const pretty = (v: unknown) => JSON.stringify(v, null, 2)

export default function App() {
  const [projects, setProjects] = useState<Project[]>([])
  const [pid, setPid] = useState<number | null>(null)
  const [scenarios, setScenarios] = useState<Scenario[]>([])
  const [sid, setSid] = useState<number | null>(null)
  const [sname, setSname] = useState('Demo purchase flow')
  const [sdef, setSdef] = useState(pretty(DEMO_SCENARIO))
  const [preset, setPreset] = useState('smoke')
  const [cfg, setCfg] = useState(pretty(PRESETS.smoke))
  const [runs, setRuns] = useState<Run[]>([])
  const [open, setOpen] = useState<Run | null>(null)
  const [cmp, setCmp] = useState<number[]>([])
  const [cmpRes, setCmpRes] = useState<Cmp[] | null>(null)
  const [pname, setPname] = useState('')
  const [purl, setPurl] = useState('http://demo-api:8080')
  const [script, setScript] = useState<string | null>(null)
  const [msg, setMsg] = useState('')

  const guard = async (fn: () => Promise<void>) => {
    try { await fn() } catch (e) { setMsg((e as Error).message) }
  }

  const loadProjects = () => guard(async () => setProjects(await api<Project[]>('/api/projects')))
  const loadScenarios = (p: number) => guard(async () => setScenarios(await api<Scenario[]>(`/api/projects/${p}/scenarios`)))
  const loadRuns = useCallback(
    () => (sid ? guard(async () => setRuns(await api<Run[]>(`/api/runs?scenarioId=${sid}`))) : Promise.resolve()),
    [sid],
  )

  useEffect(() => { loadProjects() }, [])
  useEffect(() => { setSid(null); setScenarios([]); if (pid) loadScenarios(pid) }, [pid])
  useEffect(() => { setRuns([]); setOpen(null); loadRuns() }, [loadRuns])
  useEffect(() => {
    if (!runs.some(r => r.status === 'QUEUED' || r.status === 'RUNNING')) return
    const t = setInterval(loadRuns, 2000)
    return () => clearInterval(t)
  }, [runs, loadRuns])
  useEffect(() => { if (open) { const f = runs.find(r => r.id === open.id); if (f) setOpen(f) } }, [runs])

  const createProject = () => guard(async () => {
    const p = await api<Project>('/api/projects', { method: 'POST', body: JSON.stringify({ name: pname, baseUrl: purl }) })
    setPname(''); await loadProjects(); setPid(p.id)
  })

  const pick = (s: Scenario) => { setSid(s.id); setSname(s.name); setSdef(pretty(s.definition)); setMsg('') }

  const save = () => guard(async () => {
    const body = JSON.stringify({ name: sname, definition: JSON.parse(sdef) })
    const s = sid
      ? await api<Scenario>(`/api/scenarios/${sid}`, { method: 'PUT', body })
      : await api<Scenario>(`/api/projects/${pid}/scenarios`, { method: 'POST', body })
    setSid(s.id); await loadScenarios(pid!); setMsg('Saved')
  })

  const importSpec = (f: File | undefined) => f && guard(async () => {
    const s = await api<Scenario>(`/api/projects/${pid}/import-openapi`, { method: 'POST', body: await f.text(), headers: { 'Content-Type': 'text/plain' } })
    await loadScenarios(pid!); pick(s)
  })

  const run = () => guard(async () => {
    if (!sid) throw new Error('Save the scenario first')
    await api<Run>(`/api/scenarios/${sid}/runs`, { method: 'POST', body: JSON.stringify(JSON.parse(cfg)) })
    await loadRuns()
  })

  const preview = () => guard(async () => {
    if (!sid) throw new Error('Save the scenario first')
    const r = await fetch(`/api/scenarios/${sid}/compile`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: cfg })
    setScript(await r.text())
  })

  const toggle = (id: number) => { setCmpRes(null); setCmp(c => (c.includes(id) ? c.filter(x => x !== id) : [...c, id].slice(-2))) }
  const compare = () => guard(async () => setCmpRes(await api<Cmp[]>(`/api/runs/compare?a=${cmp[0]}&b=${cmp[1]}`)))

  return (
    <>
      <header>perfkit · API → Data → Scenario → Load → Metrics → Results → Comparison</header>
      <div className="layout">
        <aside>
          <div className="card">
            <h3>Projects</h3>
            <div className="list">
              {projects.map(p => <div key={p.id} className={p.id === pid ? 'sel' : ''} onClick={() => setPid(p.id)}>{p.name}<br /><small>{p.baseUrl}</small></div>)}
            </div>
            <div className="row"><input placeholder="Name" value={pname} onChange={e => setPname(e.target.value)} /></div>
            <div className="row"><input placeholder="Base URL" value={purl} onChange={e => setPurl(e.target.value)} /></div>
            <button onClick={createProject} disabled={!pname}>Create project</button>
          </div>
          {pid && (
            <div className="card">
              <h3>Scenarios</h3>
              <div className="list">
                {scenarios.map(s => <div key={s.id} className={s.id === sid ? 'sel' : ''} onClick={() => pick(s)}>{s.name}</div>)}
              </div>
              <div className="row">
                <button className="secondary" onClick={() => { setSid(null); setSname('New scenario'); setSdef(pretty(DEMO_SCENARIO)) }}>New (demo)</button>
              </div>
              <div className="row"><small>Import OpenAPI (json/yaml)</small><input type="file" onChange={e => importSpec(e.target.files?.[0])} /></div>
            </div>
          )}
        </aside>

        <main>
          {msg && <div className="msg">{msg}</div>}
          {!pid && <div className="card">Create or select a project. For the bundled demo API use base URL <code>http://demo-api:8080</code>.</div>}
          {pid && (
            <>
              <div className="card">
                <h3>Scenario</h3>
                <div className="row"><input value={sname} onChange={e => setSname(e.target.value)} style={{ minWidth: 280 }} /><button onClick={save}>Save</button></div>
                <textarea rows={14} value={sdef} onChange={e => setSdef(e.target.value)} spellCheck={false} />
              </div>

              <div className="card">
                <h3>Load profile &amp; thresholds</h3>
                <div className="row">
                  <select value={preset} onChange={e => { setPreset(e.target.value); setCfg(pretty(PRESETS[e.target.value])) }}>
                    {Object.keys(PRESETS).map(k => <option key={k}>{k}</option>)}
                  </select>
                  <button onClick={run} disabled={!sid}>Run test</button>
                  <button className="secondary" onClick={preview} disabled={!sid}>Preview k6 script</button>
                </div>
                <textarea rows={9} value={cfg} onChange={e => setCfg(e.target.value)} spellCheck={false} />
                {script && <pre>{script}</pre>}
              </div>

              <div className="card">
                <h3>Runs</h3>
                <table>
                  <thead><tr><th></th><th>#</th><th>Status</th><th>P95</th><th>Error</th><th>Req/s</th><th></th></tr></thead>
                  <tbody>
                    {runs.map(r => (
                      <tr key={r.id}>
                        <td><input type="checkbox" checked={cmp.includes(r.id)} onChange={() => toggle(r.id)} disabled={!r.stats} /></td>
                        <td>{r.id}</td>
                        <td className={r.status === 'PASSED' ? 'pass' : r.status.startsWith('FAILED') || r.status === 'ERROR' ? 'fail' : ''}>{r.status}</td>
                        <td>{r.stats ? ms(r.stats.p95) : '–'}</td>
                        <td>{r.stats ? (r.stats.errorRate * 100).toFixed(2) + '%' : '–'}</td>
                        <td>{r.stats ? r.stats.rps.toFixed(1) : '–'}</td>
                        <td>
                          <button className="secondary" onClick={() => setOpen(r)}>Details</button>{' '}
                          {(r.status === 'RUNNING' || r.status === 'QUEUED') && <button className="secondary" onClick={() => guard(async () => { await api(`/api/runs/${r.id}/stop`, { method: 'POST' }); await loadRuns() })}>Stop</button>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="row"><button onClick={compare} disabled={cmp.length !== 2}>Compare selected (older → newer pick order)</button></div>
                {cmpRes && (
                  <table>
                    <thead><tr><th>Metric</th><th>Run {cmp[0]}</th><th>Run {cmp[1]}</th><th>Δ%</th><th>Verdict</th></tr></thead>
                    <tbody>{cmpRes.map(c => <tr key={c.metric}><td>{c.metric}</td><td>{c.a.toFixed(3)}</td><td>{c.b.toFixed(3)}</td><td>{c.deltaPct}%</td><td className={c.verdict}>{c.verdict}</td></tr>)}</tbody>
                  </table>
                )}
              </div>

              {open && <RunDetail run={open} />}
            </>
          )}
        </main>
      </div>
    </>
  )
}

function RunDetail({ run }: { run: Run }) {
  const s = run.stats
  return (
    <div className="card">
      <h3>Run #{run.id} · <span className={run.passed ? 'pass' : run.status === 'RUNNING' ? '' : 'fail'}>{run.status}</span></h3>
      {s && (
        <>
          <div className="stats">
            {([['Requests', s.requests.toFixed(0)], ['Req/s', s.rps.toFixed(1)], ['Max VUs', s.vusMax.toFixed(0)], ['Error rate', (s.errorRate * 100).toFixed(2) + '%'],
              ['Avg', ms(s.avg)], ['P50', ms(s.p50)], ['P90', ms(s.p90)], ['P95', ms(s.p95)], ['P99', ms(s.p99)], ['Max', ms(s.max)]] as [string, string][])
              .map(([k, v]) => <div className="stat" key={k}>{k}<b>{v}</b></div>)}
          </div>
          <h3>Thresholds</h3>
          {s.thresholds.length === 0 && <small>none defined</small>}
          <table><tbody>{s.thresholds.map((t, i) => <tr key={i}><td>{t.metric}</td><td>{t.expression}</td><td className={t.ok ? 'pass' : 'fail'}>{t.ok ? 'PASS' : 'FAIL'}</td></tr>)}</tbody></table>
          <h3 style={{ marginTop: 12 }}>Endpoints (slowest first)</h3>
          <table>
            <thead><tr><th>Step</th><th>Requests</th><th>Avg</th><th>P95</th><th>P99</th><th>Error</th></tr></thead>
            <tbody>{s.endpoints.map(e => <tr key={e.step}><td>{e.step}</td><td>{e.count}</td><td>{ms(e.avg)}</td><td>{ms(e.p95)}</td><td>{ms(e.p99)}</td><td>{(e.errorRate * 100).toFixed(2)}%</td></tr>)}</tbody>
          </table>
        </>
      )}
      {run.logTail && <><h3 style={{ marginTop: 12 }}>k6 log</h3><pre>{run.logTail}</pre></>}
    </div>
  )
}

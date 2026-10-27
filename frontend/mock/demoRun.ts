// A scripted analysis of the README demo question against demo_shop. The numbers are invented
// but follow the story planted in docker/mysql/init/03-demo-shop-data.sql.

export interface ScriptedEvent {
  /** Milliseconds to wait before sending this event. */
  delay: number
  event: string
  data: unknown
}

const STEP1_BROKEN = `WITH buyer_orders AS (
  SELECT c.name AS category,
         QUARTER(o.created_at) AS q,
         o.user_id,
         COUNT(*) AS order_cnt
  FROM orders o
  JOIN order_items i ON i.order_id = o.id
  JOIN products p ON p.id = i.product_id
  JOIN categories c ON c.id = p.category_id
  WHERE o.created_at >= '2026-04-01' AND o.created_at < '2026-10-01'
  GROUP BY c.name, QUARTER(o.created_at), o.user_id
)
SELECT category,
       ROUND(AVG(CASE WHEN q = 2 THEN order_cnt >= 2 END) * 100, 1) AS q2_repurchase_pct,
       ROUND(AVG(CASE WHEN q = 3 THEN order_cnt >= 2 END) * 100, 1) AS q3_repurchase_pct
FROM buyer_orders
GROUP BY category
LIMIT 1000`

const STEP1 = `WITH buyer_orders AS (
  SELECT c.name AS category,
         QUARTER(o.order_date) AS q,
         o.user_id,
         COUNT(*) AS order_cnt
  FROM orders o
  JOIN order_items i ON i.order_id = o.id
  JOIN products p ON p.id = i.product_id
  JOIN categories c ON c.id = p.category_id
  WHERE o.order_date >= '2026-04-01' AND o.order_date < '2026-10-01'
  GROUP BY c.name, QUARTER(o.order_date), o.user_id
)
SELECT category,
       ROUND(AVG(CASE WHEN q = 2 THEN order_cnt >= 2 END) * 100, 1) AS q2_repurchase_pct,
       ROUND(AVG(CASE WHEN q = 3 THEN order_cnt >= 2 END) * 100, 1) AS q3_repurchase_pct,
       ROUND((AVG(CASE WHEN q = 3 THEN order_cnt >= 2 END)
            - AVG(CASE WHEN q = 2 THEN order_cnt >= 2 END)) * 100, 1) AS change_pp
FROM buyer_orders
GROUP BY category
ORDER BY change_pp
LIMIT 1000`

const STEP2 = `WITH buyer_orders AS (
  SELECT r.name AS region,
         ch.name AS register_channel,
         QUARTER(o.order_date) AS q,
         o.user_id,
         COUNT(*) AS order_cnt
  FROM orders o
  JOIN order_items i ON i.order_id = o.id
  JOIN products p ON p.id = i.product_id
  JOIN categories c ON c.id = p.category_id
  JOIN users u ON u.id = o.user_id
  JOIN regions r ON r.id = u.region_id
  JOIN channels ch ON ch.id = u.channel_id
  WHERE c.name = '母婴'
    AND o.order_date >= '2026-04-01' AND o.order_date < '2026-10-01'
  GROUP BY r.name, ch.name, QUARTER(o.order_date), o.user_id
)
SELECT region,
       register_channel,
       ROUND(AVG(CASE WHEN q = 2 THEN order_cnt >= 2 END) * 100, 1) AS q2_repurchase_pct,
       ROUND(AVG(CASE WHEN q = 3 THEN order_cnt >= 2 END) * 100, 1) AS q3_repurchase_pct,
       ROUND((AVG(CASE WHEN q = 3 THEN order_cnt >= 2 END)
            - AVG(CASE WHEN q = 2 THEN order_cnt >= 2 END)) * 100, 1) AS change_pp
FROM buyer_orders
GROUP BY region, register_channel
ORDER BY change_pp
LIMIT 1000`

const STEP3 = `SELECT rf.reason,
       SUM(QUARTER(o.order_date) = 2) AS q2_refunds,
       SUM(QUARTER(o.order_date) = 3) AS q3_refunds
FROM refunds rf
JOIN orders o ON o.id = rf.order_id
JOIN order_items i ON i.order_id = o.id
JOIN products p ON p.id = i.product_id
JOIN categories c ON c.id = p.category_id
JOIN users u ON u.id = o.user_id
JOIN regions r ON r.id = u.region_id
WHERE c.name = '母婴' AND r.name = '华东'
  AND o.order_date >= '2026-04-01' AND o.order_date < '2026-10-01'
GROUP BY rf.reason
ORDER BY q3_refunds DESC
LIMIT 1000`

const STEP1_ROWS: [string, number, number, number][] = [
  ['母婴', 31.8, 19.6, -12.2],
  ['美妆', 27.4, 26.1, -1.3],
  ['服饰', 22.9, 22.1, -0.8],
  ['食品', 35.2, 34.9, -0.3],
  ['家居', 18.6, 18.9, 0.3],
  ['数码', 12.1, 12.8, 0.7],
]

const STEP2_ROWS: [string, string, number, number, number][] = [
  ['华东', 'App', 34.5, 8.1, -26.4],
  ['华东', '小程序', 32.7, 27.9, -4.8],
  ['华东', 'PC官网', 30.2, 27.6, -2.6],
  ['华北', 'App', 31.9, 30.8, -1.1],
  ['华南', 'App', 32.4, 31.6, -0.8],
  ['华中', '小程序', 30.1, 29.5, -0.6],
  ['西南', 'App', 29.8, 29.4, -0.4],
  ['华南', '小程序', 31.2, 30.9, -0.3],
  ['华北', 'PC官网', 28.7, 28.5, -0.2],
  ['西南', '小程序', 30.6, 30.5, -0.1],
  ['华中', 'App', 31.5, 31.5, 0],
  ['华南', 'PC官网', 29.3, 29.6, 0.3],
  ['华北', '小程序', 30.9, 31.3, 0.4],
  ['西南', 'PC官网', 28.1, 28.6, 0.5],
  ['华中', 'PC官网', 27.9, 28.6, 0.7],
]

const STEP3_ROWS: [string, number, number][] = [
  ['质量问题', 11, 217],
  ['不想要了', 12, 18],
  ['规格不符', 8, 14],
  ['物流太慢', 6, 11],
  ['其他', 4, 8],
]

export const DEMO_CONCLUSION = `## 结论

**母婴**是 2026 年第三季度复购率下降最多的品类：从 Q2 的 31.8% 降到 Q3 的 19.6%，下降 **12.2 个百分点**；其他品类的变化都在 ±1.5 个百分点以内。

## 原因

1. **下降集中在华东 App 注册用户。** 这部分用户的母婴复购率从 34.5% 跌到 8.1%（−26.4 个百分点），华东其他渠道只小幅下降，其他大区基本持平。
2. **同期华东母婴退款激增，主要是质量问题。** 华东母婴订单的退款从 Q2 的 41 笔升到 Q3 的 268 笔，其中「质量问题」217 笔，占 81%（Q2 仅占 27%）。

推测 Q3 华东母婴商品出现了质量问题：用户首单退款后不再回购，App 渠道用户受影响最大。

## 建议

- 排查 Q3 华东母婴商品的供货批次与差评，确认问题商品。
- 对受影响用户定向召回：补偿券 + 售后回访。
- 把「品类 × 大区退款率」加入日常监控，提前发现同类问题。
`

function chunk(text: string): string[] {
  const parts: string[] = []
  for (let i = 0; i < text.length; ) {
    const size = 2 + ((i * 7) % 5)
    parts.push(text.slice(i, i + size))
    i += size
  }
  return parts
}

export function demoRun(conversationId: number): ScriptedEvent[] {
  const events: ScriptedEvent[] = [
    { delay: 0, event: 'conversation', data: { conversationId } },
    {
      delay: 500,
      event: 'plan',
      data: {
        text: '先算出 2026 年 Q2 和 Q3 各品类的复购率并比较。复购率口径：季度内购买该品类不少于 2 次的用户数 ÷ 购买该品类的用户数。',
      },
    },
    { delay: 700, event: 'tables', data: { tables: ['orders', 'order_items', 'products', 'categories'] } },
    { delay: 1200, event: 'sql', data: { step: 1, attempt: 1, sql: STEP1_BROKEN } },
    {
      delay: 600,
      event: 'sql_error',
      data: { step: 1, attempt: 1, message: "Unknown column 'o.created_at' in 'field list'" },
    },
    { delay: 1000, event: 'sql', data: { step: 1, attempt: 2, sql: STEP1 } },
    {
      delay: 900,
      event: 'result',
      data: {
        step: 1,
        attempt: 2,
        columns: ['category', 'q2_repurchase_pct', 'q3_repurchase_pct', 'change_pp'],
        rows: STEP1_ROWS,
        truncated: false,
        elapsedMs: 412,
      },
    },
    {
      delay: 500,
      event: 'chart',
      data: {
        title: '各品类复购率：2026 Q2 vs Q3',
        option: {
          tooltip: { trigger: 'axis' },
          legend: { data: ['2026 Q2', '2026 Q3'] },
          grid: { left: 40, right: 16, top: 40, bottom: 24 },
          xAxis: { type: 'category', data: STEP1_ROWS.map((r) => r[0]) },
          yAxis: { type: 'value', axisLabel: { formatter: '{value}%' } },
          series: [
            { name: '2026 Q2', type: 'bar', data: STEP1_ROWS.map((r) => r[1]) },
            { name: '2026 Q3', type: 'bar', data: STEP1_ROWS.map((r) => r[2]) },
          ],
        },
      },
    },
    {
      delay: 800,
      event: 'plan',
      data: { text: '母婴下降 12.2 个百分点，远超其他品类。按用户所在大区 × 注册渠道下钻，看下降集中在哪类用户。' },
    },
    {
      delay: 600,
      event: 'tables',
      data: { tables: ['orders', 'order_items', 'products', 'categories', 'users', 'regions', 'channels'] },
    },
    { delay: 1200, event: 'sql', data: { step: 2, attempt: 1, sql: STEP2 } },
    {
      delay: 1100,
      event: 'result',
      data: {
        step: 2,
        attempt: 1,
        columns: ['region', 'register_channel', 'q2_repurchase_pct', 'q3_repurchase_pct', 'change_pp'],
        rows: STEP2_ROWS,
        truncated: false,
        elapsedMs: 638,
      },
    },
    {
      delay: 800,
      event: 'plan',
      data: { text: '下降集中在华东 App 注册用户（−26.4 个百分点）。复购骤降常伴随售后问题，查看同期华东母婴订单的退款原因。' },
    },
    {
      delay: 600,
      event: 'tables',
      data: { tables: ['refunds', 'orders', 'order_items', 'products', 'categories', 'users', 'regions'] },
    },
    { delay: 1100, event: 'sql', data: { step: 3, attempt: 1, sql: STEP3 } },
    {
      delay: 700,
      event: 'result',
      data: {
        step: 3,
        attempt: 1,
        columns: ['reason', 'q2_refunds', 'q3_refunds'],
        rows: STEP3_ROWS,
        truncated: false,
        elapsedMs: 97,
      },
    },
    {
      delay: 500,
      event: 'chart',
      data: {
        title: '华东母婴退款原因：2026 Q2 vs Q3',
        option: {
          tooltip: { trigger: 'axis' },
          legend: { data: ['2026 Q2', '2026 Q3'] },
          grid: { left: 64, right: 16, top: 40, bottom: 24 },
          xAxis: { type: 'value' },
          yAxis: { type: 'category', data: STEP3_ROWS.map((r) => r[0]).reverse() },
          series: [
            { name: '2026 Q2', type: 'bar', data: STEP3_ROWS.map((r) => r[1]).reverse() },
            { name: '2026 Q3', type: 'bar', data: STEP3_ROWS.map((r) => r[2]).reverse() },
          ],
        },
      },
    },
  ]
  chunk(DEMO_CONCLUSION).forEach((text, i) => events.push({ delay: i === 0 ? 900 : 30, event: 'token', data: { text } }))
  events.push({ delay: 200, event: 'done', data: { finalSql: STEP1, conclusion: DEMO_CONCLUSION } })
  return events
}

/** A run that fails, for exercising the error path: ask a question containing「失败」. */
export function failingRun(conversationId: number): ScriptedEvent[] {
  return [
    { delay: 0, event: 'conversation', data: { conversationId } },
    { delay: 500, event: 'plan', data: { text: '先确认需要用到的数据表。' } },
    { delay: 800, event: 'error', data: { message: '模型服务暂时不可用，请稍后重试（mock）' } },
  ]
}

/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** '1' when the dev server replays scripted analysis runs instead of proxying to the backend. */
  readonly VITE_MOCK?: string
}

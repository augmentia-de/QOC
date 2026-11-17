import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { ReactFlowProvider } from '@xyflow/react'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import App from './App.tsx'

const basename = import.meta.env.BASE_URL === '/'
  ? undefined
  : import.meta.env.BASE_URL.replace(/\/$/, '')

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter basename={basename}>
      <ReactFlowProvider>
        <App />
      </ReactFlowProvider>
    </BrowserRouter>
  </StrictMode>,
)
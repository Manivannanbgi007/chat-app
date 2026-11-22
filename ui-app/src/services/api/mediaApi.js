import { apiClient } from './apiClient'

// media-service calls (API_DOCUMENTATION.md section 6).

// Uploads one file as multipart/form-data. The answer describes the stored file; its id goes into the message.
// Content-Type is NOT set by hand: the browser adds "multipart/form-data; boundary=..." itself.
//   purpose: 'IMAGE' | 'VIDEO' | 'AUDIO' | 'VOICE' | 'DOCUMENT'
//   options: { onProgress?: (percent 0-100) => void, signal?: AbortSignal (aborting it cancels the upload) }
export async function uploadMedia(file, fileName, purpose, durationSeconds, options = {}) {
  const form = new FormData()
  form.append('file', file, fileName)
  form.append('purpose', purpose)
  if (durationSeconds !== null) form.append('durationSeconds', String(Math.round(durationSeconds)))

  const response = await apiClient.post('/api/media/upload', form, {
    signal: options.signal,
    timeout: 0, // big files take longer than the normal 15 s limit; the user can cancel instead
    onUploadProgress: (event) => {
      if (event.total) options.onProgress?.(Math.min(100, Math.round((event.loaded / event.total) * 100)))
    },
  })
  return response.data.data
}

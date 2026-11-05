import type { ApiResponse } from '../../types/api'
import type { Media, MediaKind } from '../../types/chat'
import { apiClient } from './apiClient'

// media-service calls (API_DOCUMENTATION.md section 6).

export interface UploadOptions {
  // Called while the file is sent, with 0-100.
  onProgress?: (percent: number) => void
  // Aborting this signal cancels the upload.
  signal?: AbortSignal
}

// Uploads one file as multipart/form-data. The answer describes the stored file; its id goes into the message.
// Content-Type is NOT set by hand: the browser adds "multipart/form-data; boundary=..." itself.
export async function uploadMedia(
  file: Blob,
  fileName: string,
  purpose: MediaKind,
  durationSeconds: number | null,
  options: UploadOptions = {},
): Promise<Media> {
  const form = new FormData()
  form.append('file', file, fileName)
  form.append('purpose', purpose)
  if (durationSeconds !== null) form.append('durationSeconds', String(Math.round(durationSeconds)))

  const response = await apiClient.post<ApiResponse<Media>>('/api/media/upload', form, {
    signal: options.signal,
    timeout: 0, // big files take longer than the normal 15 s limit; the user can cancel instead
    onUploadProgress: (event) => {
      if (event.total) options.onProgress?.(Math.min(100, Math.round((event.loaded / event.total) * 100)))
    },
  })
  return response.data.data
}

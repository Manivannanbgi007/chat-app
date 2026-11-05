// A fresh signed link to the photo / video of a status (or the photo's small preview), cached like message files.
import { getStatusMediaUrl } from '../../services/api/statusApi'
import { useSignedUrl } from '../media/useSignedUrl'

export function useStatusMediaUrl(statusId: string, variant: 'original' | 'thumbnail', enabled = true) {
  return useSignedUrl(['status-media-url', statusId, variant], () => getStatusMediaUrl(statusId, variant), enabled)
}

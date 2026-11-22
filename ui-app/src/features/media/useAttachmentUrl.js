// A fresh signed link to the file of a message, fetched on demand and cached (see useSignedUrl.js for the rules).
import { getAttachmentUrl } from '../../services/api/chatApi'
import { useSignedUrl } from './useSignedUrl'

// variant: 'original' | 'thumbnail'
export function useAttachmentUrl(messageId, variant, enabled = true) {
  return useSignedUrl(['attachment-url', messageId, variant], () => getAttachmentUrl(messageId, variant), enabled)
}

// A fresh signed link to the file of a message, fetched on demand and cached (see useSignedUrl.ts for the rules).
import { getAttachmentUrl } from '../../services/api/chatApi'
import { useSignedUrl } from './useSignedUrl'

export function useAttachmentUrl(messageId: string, variant: 'original' | 'thumbnail', enabled = true) {
  return useSignedUrl(['attachment-url', messageId, variant], () => getAttachmentUrl(messageId, variant), enabled)
}

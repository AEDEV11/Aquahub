export function publicAssetUrl(storage, bucket, path) {
  if (!path) return "";
  const value = String(path).trim();
  if (/^https?:\/\//i.test(value)) return value;

  let objectPath = value;
  const storedReference = value.match(/^supabase:\/\/([^/]+)\/(.+)$/i);
  if (storedReference) {
    if (storedReference[1] !== bucket || !storedReference[2]) return "";
    objectPath = storedReference[2];
  } else if (/^supabase:\/\//i.test(value)) {
    return "";
  }

  return storage.from(bucket).getPublicUrl(objectPath).data.publicUrl;
}

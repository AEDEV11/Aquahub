export type PlayTokenBinding = {
  organization_id: string | null;
  superseded_at: string | null;
};

// A verified new token can inherit only an unsuperseded predecessor's binding.
// The SQL apply function separately checks that predecessor is still current.
export function rtdnOrganizationForVerifiedToken(
  token: PlayTokenBinding | null,
  linkedToken: PlayTokenBinding | null,
): string | null {
  if (token?.superseded_at) return null;
  if (token?.organization_id) return token.organization_id;
  if (!linkedToken || linkedToken.superseded_at) return null;
  return linkedToken.organization_id;
}

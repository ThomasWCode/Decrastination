/** [jpeg]'s name, as the server and the phone name a photo (`Photos.hashOf`): the first eight bytes of its SHA-256, in hex (6.8.0). */
export async function photoHash(jpeg: Blob): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", await jpeg.arrayBuffer());
  return Array.from(new Uint8Array(digest).slice(0, 8), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

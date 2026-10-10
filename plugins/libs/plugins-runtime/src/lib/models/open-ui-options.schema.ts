import { z } from 'zod';

export const openUISchema = z.object({
  width: z.number().positive(),
  height: z.number().positive(),
  hidden: z.boolean().optional(),
  // Render the plugin inside the workspace plugin dock (an integrated,
  // full-height side panel) instead of a floating window. Falls back to the
  // floating window when the host provides no dock container.
  dock: z.boolean().optional(),
});

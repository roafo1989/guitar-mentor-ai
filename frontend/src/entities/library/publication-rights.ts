import {
  provenance,
  type AssetRight,
  type RightsBasis,
} from "./community.service";

export interface RightsForm {
  assetId: string;
  chosen: boolean;
  choice: "" | "OWN" | "PERMISSION" | "OTHER";
  basis: "" | RightsBasis;
  author: string;
  note: string;
}

// These are the uploader's explicit declarations, not independently verified proof.
// Keep canonical text independent of UI locale so identical retries keep their payload.
export function declaredRights(form: RightsForm): AssetRight | null {
  const author = form.author.trim(),
    note = form.note.trim();
  if (author.length > 150) return null;
  let basis: RightsBasis, evidence: string;
  if (form.choice === "OWN") {
    basis = "OWN_WORK";
    evidence =
      "Uploader declares authorship and authorizes sharing this file with admitted users.";
  } else if (form.choice === "PERMISSION") {
    if (!author || note.length > 250) return null;
    basis = "PERMISSION";
    evidence =
      "Uploader declares permission to share from author: " +
      author +
      (note ? ". Comment: " + note : "");
  } else if (
    form.choice === "OTHER" &&
    ["LICENSE", "PUBLIC_DOMAIN"].includes(form.basis)
  ) {
    basis = form.basis as RightsBasis;
    if (note.length < 8 || note.length > 500) return null;
    evidence = note;
  } else return null;
  return {
    assetId: form.assetId,
    rightsBasis: basis,
    provenance: provenance[basis],
    rightsEvidence: evidence,
    attribution: author,
  };
}

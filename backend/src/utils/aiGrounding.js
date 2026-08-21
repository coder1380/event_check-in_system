/**
 * Validates that all numeric values in an AI-generated answer are present
 * in the stringified or flattened raw stats data payload (grounding check).
 *
 * @param {string} answer - The text answer returned by the AI model.
 * @param {object} rawStats - The raw stats JSON payload provided to the prompt.
 * @returns {boolean} - Returns true if every number in the answer is grounded in rawStats.
 */
function validateGrounding(answer, rawStats) {
  if (typeof answer !== 'string' || !answer.trim()) return false;
  if (!rawStats || typeof rawStats !== 'object') return false;

  const digitsInResponse = (answer.match(/\d+(?:\.\d+)?/g) ?? []).map(Number);
  if (digitsInResponse.length === 0) return true;

  function flattenNumbers(obj) {
    const nums = [];
    for (const val of Object.values(obj ?? {})) {
      if (typeof val === 'number') nums.push(val);
      else if (Array.isArray(val)) val.forEach((item) => nums.push(...flattenNumbers(item)));
      else if (typeof val === 'object' && val !== null) nums.push(...flattenNumbers(val));
    }
    return nums;
  }

  const numsInStats = flattenNumbers(rawStats);
  const timeNums = (JSON.stringify(rawStats).match(/\d+/g) ?? []).map(Number);
  const allowedNums = [...new Set([...numsInStats, ...timeNums])];

  for (const num of digitsInResponse) {
    // Exempt small benign numbers (1–3, e.g. sentence numbers or small ordinals)
    if (num <= 3) continue;

    const isAllowed = allowedNums.some((n) => Math.abs(n - num) < 0.15);
    if (!isAllowed) {
      return false;
    }
  }

  return true;
}

module.exports = {
  validateGrounding,
};

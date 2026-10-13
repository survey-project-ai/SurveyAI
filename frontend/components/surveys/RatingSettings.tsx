import { getRatingRange } from "@/lib/survey-builder";
import type { SurveyQuestionType } from "@/lib/types";

export function RatingSettings({ type }: { type: SurveyQuestionType }) {
  const range = getRatingRange(type);

  return (
    <div className="builder-field-group">
      <strong>Ölçek önizlemesi</strong>
      <div className="rating-scale-preview">
        {range.map((value) => (
          <span key={value} className="rating-pill">
            {value}
          </span>
        ))}
      </div>
    </div>
  );
}

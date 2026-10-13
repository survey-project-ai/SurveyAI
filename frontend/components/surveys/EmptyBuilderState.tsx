"use client";

import { PlusIcon } from "@/components/ui/Icons";

export function EmptyBuilderState({ onAdd, disabled = false }: { onAdd: () => void; disabled?: boolean }) {
  return (
    <div className="empty-builder-state ops-builder-empty-state">
      <div className="empty-builder-icon">
        <PlusIcon className="nav-icon" />
      </div>
      <strong>{disabled ? "Bu anket yalnızca görüntülenebilir" : "İlk soruyu ekleyin"}</strong>
      <p>
        {disabled
          ? "Yayınlanmış anketlerde soru yapısı değiştirilemez. İçeriği inceleyebilir, yeni taslak akışının eklenmesini bekleyebilirsiniz."
          : "Görüşme akışının ilk karar noktasını tanımlayın. Sistem soru eklendikçe önizlemeyi ve hazırlık durumunu anında günceller."}
      </p>
      <button type="button" className="button-primary compact-button" onClick={onAdd} disabled={disabled}>
        <PlusIcon className="nav-icon" />
        {disabled ? "Yeni taslak yakında" : "İlk soruyu ekle"}
      </button>
    </div>
  );
}

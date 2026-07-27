export function RequestCard() {
  return (
    <div className="flex items-center justify-between p-4 border-b-thin">
      <div className="flex-1">
        <div className="text-xs text-on-surface-variant mb-1 flex items-center gap-1">
          <span className="bg-teal-100 text-teal-700 px-1 rounded">퀵</span> 중형
        </div>
        <div className="flex justify-between items-end">
          <div>
            <div className="font-bold text-lg"><span className="text-base font-normal">758m</span> 동작 노량진2</div>
          </div>
          <div className="text-right">
            <div className="text-sm text-on-surface-variant">중구</div>
            <div className="font-bold">중림</div>
          </div>
        </div>
      </div>
      <div className="ml-4 text-xl font-bold">
        6,860
      </div>
    </div>
  )
}

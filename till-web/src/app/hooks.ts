import { useDispatch, useSelector } from 'react-redux'
import type { AppDispatch, RootState } from './store'

/** Typed once, so no component has to annotate a selector's state or a dispatch's actions. */
export const useAppDispatch = useDispatch.withTypes<AppDispatch>()
export const useAppSelector = useSelector.withTypes<RootState>()

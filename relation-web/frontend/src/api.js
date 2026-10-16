import axios from 'axios'

const api = axios.create({ baseURL: '/api', timeout: 15000 })

export function getDirectRelatives(gmsfhm, level = 0) {
  return api.get('/person/direct', { params: { gmsfhm, level } })
}

export function getRelationPath(from, to, level = 0) {
  return api.get('/relation/path', { params: { from, to, level } })
}

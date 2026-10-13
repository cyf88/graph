import axios from 'axios'

const api = axios.create({ baseURL: '/api', timeout: 15000 })

export function getDirectRelatives(gmsfhm) {
  return api.get('/person/direct', { params: { gmsfhm } })
}

export function getRelationPath(from, to) {
  return api.get('/relation/path', { params: { from, to } })
}

// #ifdef H5
export const BASE_URL = `${location.protocol}//${location.host}/wxmapi`
// #endif
// #ifndef H5
// 请求接口
export const BASE_URL = 'https://www.wxmblog.com/wxmapi'; //正式接口  
//export const BASE_URL = 'http://192.168.31.121:8088'; //本地接口  
// #endif

// #ifdef MP-WEIXIN
export const AMAPKEY = '7528e756feaebfabd1abbb1b04097a1e'; //高德定位小程序
// #endif
// #ifdef APP-PLUS
export const AMAPKEY = 'cdd98f785c3d27a17bf4d7022783ede9'; //高德定位APP
// #endif

const pendingRequests = new Map()

const isPlainObject = (value) => Object.prototype.toString.call(value) === '[object Object]'

const stringifyRequestData = (value) => {
	if (Array.isArray(value)) {
		return `[${value.map(item => stringifyRequestData(item)).join(',')}]`
	}
	if (isPlainObject(value)) {
		return `{${Object.keys(value).sort().map(key => `${key}:${stringifyRequestData(value[key])}`).join(',')}}`
	}
	if (value === undefined) {
		return 'undefined'
	}
	if (value === null) {
		return 'null'
	}
	return `${value}`
}

const getRequestKey = (options) => {
	if (options.requestKey) {
		return options.requestKey
	}
	return [
		options.method || 'GET',
		options.url,
		stringifyRequestData(options.data || {}),
		options.withToken ? uni.getStorageSync('token') || '' : ''
	].join('::')
}

export const myRequest = (options = {}) => {
	if (options.withToken) {
		options.data = {
			...options.data
		}
	}
	if (options.withLoading) {
		uni.showLoading({
			title: '加载中'
		})
	}

	const requestMode = options.requestMode || 'none'
	const shouldManagePending = requestMode !== 'none'
	const requestKey = shouldManagePending ? getRequestKey(options) : ''
	const currentPending = shouldManagePending ? pendingRequests.get(requestKey) : null

	if (currentPending && requestMode === 'share') {
		return currentPending.promise
	}

	if (currentPending && requestMode === 'cancel-previous' && currentPending.requestTask) {
		currentPending.requestTask.abort()
	}

	const requestRecord = {
		promise: null,
		requestTask: null
	}

	const requestPromise = new Promise((resolve, reject) => {
		requestRecord.requestTask = uni.request({
			url: BASE_URL + '/' + options.url,
			method: options.method || 'GET',
			data: options.data || {},
			header: {
				Authorization: options.withToken ? uni.getStorageSync('token') : '',
			},
			success: (res) => {
				if (res.data.code == 2) {
					uni.showToast({
						icon: 'none',
						title: '登录失效，请重新登录',
						duration: 2000
					})
					setTimeout(() => {
						uni.reLaunch({
							url: '/pages/tab/index'
						})
					}, 200)
				}
				resolve(res)
			},
			fail: (err) => {
				if (!(err && err.errMsg && err.errMsg.indexOf('abort') !== -1)) {
					uni.showToast({
						title: '请求接口失败',
					})
				}
				reject(err)
			},
			complete() {
				if (shouldManagePending && pendingRequests.get(requestKey) === requestRecord) {
					pendingRequests.delete(requestKey)
				}
				uni.hideLoading()
			}
		})
	})

	requestRecord.promise = requestPromise

	if (shouldManagePending) {
		pendingRequests.set(requestKey, requestRecord)
	}

	return requestPromise
}
//登录判断
export const getId = () => {
	return new Promise((resolve, reject) => {
		uni.getStorage({
			key: 'info',
			success: (res) => {
				console.log(res, '登录成功')
				resolve(200)
			},
			fail: (err) => {
				console.log(err, '登录失败')
				resolve(11003)
			}
		})
	})
}

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


const pendingRequests = new Map();

export const myRequest = (options) => {
	if (options && options.withToken) {
		options.data = {
			...options.data
		};
	}
	if(options.withLoading){
		uni.showLoading({
			title: '加载中'
		});
	}
	
	const requestKey = options.cancelKey || `${options.url}_${JSON.stringify(options.data || {})}`;

	return new Promise((resolve, reject) => {
		if (pendingRequests.has(requestKey)) {
			const prevReq = pendingRequests.get(requestKey);
			prevReq.task.abort();
		}

		const currentReq = {
			resolves: [],
			rejects: [],
			task: null
		};

		if (pendingRequests.has(requestKey)) {
			currentReq.resolves = pendingRequests.get(requestKey).resolves;
			currentReq.rejects = pendingRequests.get(requestKey).rejects;
		}

		currentReq.resolves.push(resolve);
		currentReq.rejects.push(reject);
		pendingRequests.set(requestKey, currentReq);

		currentReq.task = uni.request({
			url: BASE_URL + "/"+options.url,
			method: options.method || "GET",
			data: options.data || {},
			header: {
				Authorization:options.withToken? uni.getStorageSync("token"):'',
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
							url:"/pages/tab/index"
						})
					}, 200);
				}
				if (pendingRequests.get(requestKey) === currentReq) {
					pendingRequests.delete(requestKey);
					currentReq.resolves.forEach(r => r(res));
				}
			},
			fail: (err) => {
				if (err && err.errMsg && err.errMsg.indexOf('abort') !== -1) {
					return;
				}
				uni.showToast({
					title: "请求接口失败",
				});
				if (pendingRequests.get(requestKey) === currentReq) {
					pendingRequests.delete(requestKey);
					currentReq.rejects.forEach(r => r(err));
				} else {
					reject(err);
				}
			},
			complete() {
				uni.hideLoading();
			}
		});
	});
};
//登录判断
export const getId = () => {
	return new Promise((resolve, reject) => {
		uni.getStorage({
			key: 'info',
			success: (res) => {
				console.log(res, "登录成功");
				resolve(200);
			},
			fail: (err) => {
				console.log(err, '登录失败');
				resolve(11003);
			}
		});
	})
}